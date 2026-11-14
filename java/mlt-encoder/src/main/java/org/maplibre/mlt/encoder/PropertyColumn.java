package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * The values of one property key in a layer. The column type is picked from the values once the
 * layer is complete: booleans, integers (32 or 64-bit), floats or doubles when every value has that
 * type, doubles for a mix of numbers, and strings for anything else.
 */
final class PropertyColumn {

  private static final int BOOL = 1;
  private static final int INT = 2;
  private static final int FLOAT = 4;
  private static final int DOUBLE = 8;
  private static final int STRING = 16;

  private static final int TYPE_BOOL = 10;
  private static final int TYPE_INT32 = 16;
  private static final int TYPE_INT64 = 20;
  private static final int TYPE_FLOAT = 24;
  private static final int TYPE_DOUBLE = 26;
  private static final int TYPE_STRING = 28;

  private static final int PRESENT_STREAM = 0x00;
  private static final int DATA_STREAM = 0x10;
  private static final int DICTIONARY_DATA_STREAM = 0x11;
  private static final int STRING_CODES_STREAM = 0x22;
  private static final int STRING_LENGTHS_STREAM = 0x30;
  private static final int DICTIONARY_LENGTHS_STREAM = 0x36;
  private static final int SYMBOL_TABLE_STREAM = 0x15;
  private static final int SYMBOL_LENGTHS_STREAM = 0x35;
  private static final int PLAIN_ENCODING = 0;

  /**
   * FSST is only tried on dictionaries of at least this many bytes, as the Rust encoder does:
   * training on smaller ones costs much more time than the few bytes it saves.
   */
  private static final int MIN_FSST_BYTES = 2048;

  private final byte[] name;
  private int[] features = new int[16];
  private Object[] values = new Object[16];
  private int count;
  private int kinds;
  private long minInt = Long.MAX_VALUE;
  private long maxInt = Long.MIN_VALUE;

  PropertyColumn(String name) {
    this.name = name.getBytes(StandardCharsets.UTF_8);
  }

  /** Sets the value of {@code feature}, which must be the latest feature that got one. */
  void set(int feature, Object value) {
    if (value == null) {
      return;
    }
    if (count > 0 && features[count - 1] == feature) {
      count--;
    } else if (count == features.length) {
      features = Arrays.copyOf(features, count * 2);
      values = Arrays.copyOf(values, count * 2);
    }
    int kind = kindOf(value);
    if (kind == INT) {
      long number = ((Number) value).longValue();
      minInt = Math.min(minInt, number);
      maxInt = Math.max(maxInt, number);
    }
    kinds |= kind;
    features[count] = feature;
    values[count++] = value;
  }

  /** Writes the column's metadata to {@code meta} and its streams to {@code data}. */
  void writeTo(ByteWriter meta, ByteWriter data, int featureCount, Codecs codecs) {
    int type = type();
    boolean nullable = count < featureCount;
    meta.writeByte(nullable ? type + 1 : type);
    meta.writeString(name);
    if (type == TYPE_STRING) {
      writeStrings(data, nullable, featureCount, codecs);
      return;
    }
    if (nullable) {
      writePresent(data, featureCount, codecs.booleans);
    }
    switch (type) {
      case TYPE_BOOL -> {
        codecs.booleans.clear();
        for (int i = 0; i < count; i++) {
          codecs.booleans.add((Boolean) values[i]);
        }
        codecs.booleans.writeTo(data, DATA_STREAM);
      }
      case TYPE_INT32, TYPE_INT64 -> {
        LongList ints = codecs.longs(0);
        for (int i = 0; i < count; i++) {
          ints.add(((Number) values[i]).longValue());
        }
        IntegerStream.of(ints, true, type == TYPE_INT64, codecs).writeTo(data, DATA_STREAM);
      }
      case TYPE_FLOAT -> {
        writePlainHeader(data, DATA_STREAM, count, 4L * count);
        for (int i = 0; i < count; i++) {
          data.writeIntLE(Float.floatToRawIntBits((Float) values[i]));
        }
      }
      default -> {
        writePlainHeader(data, DATA_STREAM, count, 8L * count);
        for (int i = 0; i < count; i++) {
          data.writeLongLE(Double.doubleToRawLongBits(((Number) values[i]).doubleValue()));
        }
      }
    }
  }

  private int type() {
    if (kinds == BOOL) {
      return TYPE_BOOL;
    } else if (kinds == INT) {
      return minInt >= Integer.MIN_VALUE && maxInt <= Integer.MAX_VALUE ? TYPE_INT32 : TYPE_INT64;
    } else if (kinds == FLOAT) {
      return TYPE_FLOAT;
    } else if ((kinds & ~(INT | FLOAT | DOUBLE)) == 0) {
      return TYPE_DOUBLE;
    }
    return TYPE_STRING;
  }

  /**
   * Writes the plain layout (each value's bytes) or the dictionary layout (each distinct value
   * once, plus a code per value), whichever is smaller.
   */
  private void writeStrings(ByteWriter data, boolean nullable, int featureCount, Codecs codecs) {
    var codesByValue = new HashMap<String, Integer>();
    List<byte[]> dictionary = new ArrayList<>();
    LongList codes = codecs.longs(0);
    LongList plainLengths = codecs.longs(1);
    LongList dictionaryLengths = codecs.longs(2);
    long plainBytes = 0;
    long dictionaryBytes = 0;
    for (int i = 0; i < count; i++) {
      String value = values[i] instanceof String string ? string : String.valueOf(values[i]);
      Integer code = codesByValue.get(value);
      if (code == null) {
        code = dictionary.size();
        codesByValue.put(value, code);
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        dictionary.add(utf8);
        dictionaryLengths.add(utf8.length);
        dictionaryBytes += utf8.length;
      }
      int length = dictionary.get(code).length;
      codes.add(code);
      plainLengths.add(length);
      plainBytes += length;
    }
    var plainLengthsStream = IntegerStream.of(plainLengths, false, false, codecs);
    var dictionaryLengthsStream = IntegerStream.of(dictionaryLengths, false, false, codecs);
    var codesStream = IntegerStream.of(codes, false, false, codecs);
    long plainSize = plainLengthsStream.size() + dataStreamSize(count, plainBytes);
    long dictionarySize =
        dictionaryLengthsStream.size()
            + codesStream.size()
            + dataStreamSize(dictionary.size(), dictionaryBytes);
    IntegerStream symbolLengthsStream = null;
    long fsstSize = Long.MAX_VALUE;
    Fsst fsst = codecs.fsst;
    if (fsst != null && dictionaryBytes >= MIN_FSST_BYTES) {
      byte[] text = codecs.text((int) dictionaryBytes + Fsst.PADDING);
      int position = 0;
      for (byte[] utf8 : dictionary) {
        System.arraycopy(utf8, 0, text, position, utf8.length);
        position += utf8.length;
      }
      fsst.compress(text, dictionaryLengths);
      symbolLengthsStream = IntegerStream.of(fsst.trainedSymbolLengths, false, false, codecs);
      fsstSize =
          symbolLengthsStream.size()
              + dataStreamSize(fsst.trainedSymbolLengths.size(), fsst.trainedSymbols.size())
              + dictionaryLengthsStream.size()
              + dataStreamSize(dictionary.size(), fsst.compressed.size())
              + codesStream.size();
    }

    if (fsstSize < Math.min(plainSize, dictionarySize)) {
      data.writeVarint((nullable ? 1 : 0) + 5);
      if (nullable) {
        writePresent(data, featureCount, codecs.booleans);
      }
      symbolLengthsStream.writeTo(data, SYMBOL_LENGTHS_STREAM);
      writePlainHeader(
          data, SYMBOL_TABLE_STREAM, fsst.trainedSymbolLengths.size(), fsst.trainedSymbols.size());
      data.writeBytes(fsst.trainedSymbols);
      dictionaryLengthsStream.writeTo(data, DICTIONARY_LENGTHS_STREAM);
      writePlainHeader(data, DICTIONARY_DATA_STREAM, dictionary.size(), fsst.compressed.size());
      data.writeBytes(fsst.compressed);
      codesStream.writeTo(data, STRING_CODES_STREAM);
    } else if (dictionarySize < plainSize) {
      data.writeVarint((nullable ? 1 : 0) + 3);
      if (nullable) {
        writePresent(data, featureCount, codecs.booleans);
      }
      dictionaryLengthsStream.writeTo(data, DICTIONARY_LENGTHS_STREAM);
      codesStream.writeTo(data, STRING_CODES_STREAM);
      writePlainHeader(data, DICTIONARY_DATA_STREAM, dictionary.size(), dictionaryBytes);
      for (byte[] utf8 : dictionary) {
        data.writeBytes(utf8);
      }
    } else {
      data.writeVarint((nullable ? 1 : 0) + 2);
      if (nullable) {
        writePresent(data, featureCount, codecs.booleans);
      }
      plainLengthsStream.writeTo(data, STRING_LENGTHS_STREAM);
      writePlainHeader(data, DATA_STREAM, count, plainBytes);
      for (int i = 0; i < count; i++) {
        data.writeBytes(dictionary.get((int) codes.get(i)));
      }
    }
  }

  private static long dataStreamSize(int valueCount, long byteLength) {
    return 2 + ByteWriter.varintSize(valueCount) + ByteWriter.varintSize(byteLength) + byteLength;
  }

  private void writePresent(ByteWriter data, int featureCount, BooleanStream present) {
    present.clear();
    int next = 0;
    for (int feature = 0; feature < featureCount; feature++) {
      boolean isPresent = next < count && features[next] == feature;
      if (isPresent) {
        next++;
      }
      present.add(isPresent);
    }
    present.writeTo(data, PRESENT_STREAM);
  }

  private static void writePlainHeader(
      ByteWriter data, int streamType, int valueCount, long byteLength) {
    data.writeByte(streamType);
    data.writeByte(PLAIN_ENCODING);
    data.writeVarint(valueCount);
    data.writeVarint(byteLength);
  }

  private static int kindOf(Object value) {
    return switch (value) {
      case Boolean ignored -> BOOL;
      case Integer ignored -> INT;
      case Long ignored -> INT;
      case Short ignored -> INT;
      case Byte ignored -> INT;
      case Float ignored -> FLOAT;
      case Double ignored -> DOUBLE;
      default -> STRING;
    };
  }
}
