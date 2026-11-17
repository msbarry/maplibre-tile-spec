package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.maplibre.mlt.converter.encodings.fsst.SymbolTable;

/**
 * Writes layers as MLT v1 (docs/specification/v1.md): a header, every column's metadata, then every
 * column's streams, each stream with a stream type and encoding header.
 */
final class V1LayerWriter implements LayerWriter {

  private static final int LAYER_TAG = 1;

  // Column types, each nullable variant one more.
  private static final int TYPE_ID = 0;
  private static final int TYPE_LONG_ID = 2;
  private static final int TYPE_GEOMETRY = 4;
  private static final int TYPE_BOOL = 10;
  private static final int TYPE_INT32 = 16;
  private static final int TYPE_INT64 = 20;
  private static final int TYPE_FLOAT = 24;
  private static final int TYPE_DOUBLE = 26;
  private static final int TYPE_STRING = 28;
  private static final int TYPE_SHARED_DICTIONARY = 30;

  // Stream types: the category in the high nibble, the subtype in the low one.
  private static final int PRESENT = 0x00;
  private static final int DATA = 0x10;
  private static final int DATA_SINGLE_DICTIONARY = 0x11;
  private static final int DATA_SHARED_DICTIONARY = 0x12;
  private static final int DATA_VERTEX = 0x13;
  private static final int DATA_FSST = 0x15;
  private static final int OFFSET_INDEX = 0x21;
  private static final int OFFSET_STRING = 0x22;
  private static final int LENGTH_VAR_BINARY = 0x30;
  private static final int LENGTH_GEOMETRIES = 0x31;
  private static final int LENGTH_PARTS = 0x32;
  private static final int LENGTH_RINGS = 0x33;
  private static final int LENGTH_TRIANGLES = 0x34;
  private static final int LENGTH_SYMBOL = 0x35;
  private static final int LENGTH_DICTIONARY = 0x36;

  private static final int PLAIN_ENCODING = 0;
  private static final int COMPONENTWISE_DELTA_VARINT = 0b010_000_10;
  private static final int COMPONENTWISE_DELTA_FASTPFOR = 0b010_000_01;

  private final MltEncoder.Options options;
  private final Codecs codecs;
  private final ByteWriter meta = new ByteWriter();
  private final ByteWriter data = new ByteWriter();
  private final GeometryStreams geometry = new GeometryStreams();
  private final Triangles triangles = new Triangles();
  private final Bits bits = new Bits();
  private final V1BooleanStream booleanStream = new V1BooleanStream();

  V1LayerWriter(MltEncoder.Options options, Codecs codecs) {
    this.options = options;
    this.codecs = codecs;
  }

  @Override
  public MltEncoder.LayerSize write(Layer layer, ByteWriter tile) {
    meta.clear();
    data.clear();
    List<ColumnGroup> groups = ColumnGroup.of(layer.columns, options.sharedDictionaries());
    int columnCount = 1 + groups.size();
    if (layer.ids.count() > 0) {
      columnCount++;
      writeIds(layer);
    }
    meta.writeByte(TYPE_GEOMETRY);
    writeGeometry(layer.geometries);
    int beforeProperties = meta.size() + data.size();
    for (ColumnGroup group : groups) {
      if (group.isSharedDictionary()) {
        writeSharedDictionary(group, layer.featureCount());
      } else {
        writeColumn(group.members().getFirst(), layer.featureCount());
      }
    }
    int propertyBytes = meta.size() + data.size() - beforeProperties;

    byte[] name = layer.name.getBytes(StandardCharsets.UTF_8);
    int frameSize =
        1
            + ByteWriter.varintSize(name.length)
            + name.length
            + ByteWriter.varintSize(layer.extent)
            + ByteWriter.varintSize(columnCount)
            + meta.size()
            + data.size();
    int start = tile.size();
    tile.writeVarint(frameSize);
    tile.writeByte(LAYER_TAG);
    tile.writeString(name);
    tile.writeVarint(layer.extent);
    tile.writeVarint(columnCount);
    tile.writeBytes(meta);
    tile.writeBytes(data);
    return new MltEncoder.LayerSize(layer.name, tile.size() - start, propertyBytes);
  }

  private void writeIds(Layer layer) {
    IdColumn ids = layer.ids;
    boolean nullable = ids.count() < layer.featureCount();
    int type = ids.fitIn32Bits() ? TYPE_ID : TYPE_LONG_ID;
    meta.writeByte(nullable ? type + 1 : type);
    if (nullable) {
      ids.presence(layer.featureCount(), bits);
      booleanStream.write(data, PRESENT, bits);
    }
    V1IntegerStream.of(ids.ids(), false, !ids.fitIn32Bits(), codecs).writeTo(data, DATA);
  }

  // Geometry

  private void writeGeometry(Geometries geometries) {
    geometry.fill(geometries, codecs.fastPforEnabled());
    boolean tessellated = false;
    if (options.tessellation() != Tessellation.NONE && geometry.hasPolygons) {
      triangles.fill(geometries);
      tessellated = triangles.triangleCount() > 0;
    }
    int streamCount =
        2
            + (geometry.hasMulti ? 1 : 0)
            + (geometry.hasPolygons ? 2 : geometry.hasLines ? 1 : 0)
            + (tessellated ? 2 : 0);
    data.writeVarint(streamCount);
    // The Rust and Java reference encoders both tag the types stream Length/VarBinary.
    writeInts(geometry.types, LENGTH_VAR_BINARY);
    if (geometry.hasMulti) {
      writeInts(geometry.geometryLengths, LENGTH_GEOMETRIES);
    }
    if (geometry.hasLines || geometry.hasPolygons) {
      writeInts(geometry.partLengths, LENGTH_PARTS);
    }
    if (geometry.hasPolygons) {
      writeInts(geometry.ringLengths, LENGTH_RINGS);
    }
    if (tessellated) {
      writeInts(triangles.countPerPolygonFeature, LENGTH_TRIANGLES);
      writeInts(featureRelativeIndices(), OFFSET_INDEX);
    }
    writeVertices(geometries.vertexCount());
  }

  /** v1 counts each triangle index from the first vertex of its own feature. */
  private LongList featureRelativeIndices() {
    var result = new LongList();
    int index = 0;
    for (int f = 0; f < triangles.countPerPolygonFeature.size(); f++) {
      int firstVertex = triangles.firstVertexPerPolygonFeature.get(f);
      int end = index + 3 * (int) triangles.countPerPolygonFeature.get(f);
      for (; index < end; index++) {
        result.add(triangles.indices.get(index) - firstVertex);
      }
    }
    return result;
  }

  private void writeVertices(int vertexCount) {
    int wordCount = 2 * vertexCount;
    long varintBytes = geometry.vertexVarints.size();
    data.writeByte(DATA_VERTEX);
    if (codecs.fastPforEnabled() && wordCount >= V1FastPfor.MIN_VALUES) {
      int[] encoded = codecs.v1FastPfor().encode(geometry.vertexWords.array(), wordCount);
      if (4L * encoded.length < varintBytes) {
        data.writeByte(COMPONENTWISE_DELTA_FASTPFOR);
        data.writeVarint(wordCount);
        data.writeVarint(4L * encoded.length);
        V1FastPfor.write(data, encoded);
        return;
      }
    }
    data.writeByte(COMPONENTWISE_DELTA_VARINT);
    data.writeVarint(wordCount);
    data.writeVarint(varintBytes);
    data.writeBytes(geometry.vertexVarints);
  }

  // Properties

  private void writeColumn(PropertyColumn column, int featureCount) {
    PropertyColumn.ValueType valueType = column.valueType();
    boolean nullable = column.count() < featureCount;
    int type =
        switch (valueType) {
          case BOOL -> TYPE_BOOL;
          case INT32 -> TYPE_INT32;
          case INT64 -> TYPE_INT64;
          case FLOAT -> TYPE_FLOAT;
          case DOUBLE -> TYPE_DOUBLE;
          case STRING -> TYPE_STRING;
        };
    meta.writeByte(nullable ? type + 1 : type);
    meta.writeString(column.nameUtf8);
    if (valueType == PropertyColumn.ValueType.STRING) {
      writeStrings(column, nullable, featureCount);
      return;
    }
    if (nullable) {
      writePresent(column, featureCount);
    }
    switch (valueType) {
      case BOOL -> {
        bits.clear();
        for (int i = 0; i < column.count(); i++) {
          bits.add((Boolean) column.value(i));
        }
        booleanStream.write(data, DATA, bits);
      }
      case INT32, INT64 -> {
        var ints = new LongList();
        for (int i = 0; i < column.count(); i++) {
          ints.add(((Number) column.value(i)).longValue());
        }
        V1IntegerStream.of(ints, true, valueType == PropertyColumn.ValueType.INT64, codecs)
            .writeTo(data, DATA);
      }
      case FLOAT -> {
        writePlainHeader(DATA, column.count(), 4L * column.count());
        for (int i = 0; i < column.count(); i++) {
          data.writeIntLE(Float.floatToRawIntBits((Float) column.value(i)));
        }
      }
      default -> {
        writePlainHeader(DATA, column.count(), 8L * column.count());
        for (int i = 0; i < column.count(); i++) {
          data.writeLongLE(Double.doubleToRawLongBits(((Number) column.value(i)).doubleValue()));
        }
      }
    }
  }

  /**
   * Writes the plain layout (each value's bytes), the dictionary layout (each distinct value once,
   * plus a code per value) or the FSST dictionary layout, whichever is smallest.
   */
  private void writeStrings(PropertyColumn column, boolean nullable, int featureCount) {
    var codes = new LongList();
    var dictionary = StringDictionary.of(column, codes);
    var plainLengths = new LongList();
    long plainBytes = 0;
    for (int i = 0; i < codes.size(); i++) {
      long length = dictionary.entryLengths.get((int) codes.get(i));
      plainLengths.add(length);
      plainBytes += length;
    }
    var plainLengthsStream = V1IntegerStream.of(plainLengths, false, false, codecs);
    var dictionaryLengthsStream = V1IntegerStream.of(dictionary.entryLengths, false, false, codecs);
    var codesStream = V1IntegerStream.of(codes, false, false, codecs);
    long plainSize = plainLengthsStream.size() + plainStreamSize(column.count(), plainBytes);
    long dictionarySize =
        dictionaryLengthsStream.size()
            + codesStream.size()
            + plainStreamSize(dictionary.size(), dictionary.entryBytes);
    SymbolTable fsst = dictionary.compress(codecs);
    V1IntegerStream symbolLengthsStream = fsst == null ? null : symbolLengthsStream(fsst);
    long fsstSize =
        fsst == null
            ? Long.MAX_VALUE
            : fsstStreamsSize(fsst, symbolLengthsStream, dictionaryLengthsStream, dictionary)
                + codesStream.size();
    int present = nullable ? 1 : 0;
    if (fsstSize < Math.min(plainSize, dictionarySize)) {
      data.writeVarint(present + 5);
      if (nullable) {
        writePresent(column, featureCount);
      }
      writeFsstStreams(fsst, symbolLengthsStream, dictionaryLengthsStream, dictionary);
      codesStream.writeTo(data, OFFSET_STRING);
    } else if (dictionarySize < plainSize) {
      data.writeVarint(present + 3);
      if (nullable) {
        writePresent(column, featureCount);
      }
      dictionaryLengthsStream.writeTo(data, LENGTH_DICTIONARY);
      codesStream.writeTo(data, OFFSET_STRING);
      writePlainEntries(DATA_SINGLE_DICTIONARY, dictionary);
    } else {
      data.writeVarint(present + 2);
      if (nullable) {
        writePresent(column, featureCount);
      }
      plainLengthsStream.writeTo(data, LENGTH_VAR_BINARY);
      writePlainHeader(DATA, column.count(), plainBytes);
      for (int i = 0; i < codes.size(); i++) {
        data.writeBytes(dictionary.entries.get((int) codes.get(i)));
      }
    }
  }

  /**
   * Writes a shared dictionary column: the dictionary, plain or FSST-compressed, whichever is
   * smaller, then each member as a code per value.
   */
  private void writeSharedDictionary(ColumnGroup group, int featureCount) {
    var dictionary = new StringDictionary();
    List<PropertyColumn> members = group.members();
    LongList[] codes = new LongList[members.size()];
    for (int m = 0; m < members.size(); m++) {
      codes[m] = new LongList();
      dictionary.addAll(members.get(m), codes[m]);
    }
    var dictionaryLengthsStream = V1IntegerStream.of(dictionary.entryLengths, false, false, codecs);
    long plainSize =
        dictionaryLengthsStream.size() + plainStreamSize(dictionary.size(), dictionary.entryBytes);
    SymbolTable fsst = dictionary.compress(codecs);
    V1IntegerStream symbolLengthsStream = fsst == null ? null : symbolLengthsStream(fsst);
    boolean useFsst =
        fsst != null
            && fsstStreamsSize(fsst, symbolLengthsStream, dictionaryLengthsStream, dictionary)
                < plainSize;

    meta.writeByte(TYPE_SHARED_DICTIONARY);
    meta.writeString(group.prefix().getBytes(StandardCharsets.UTF_8));
    meta.writeVarint(members.size());
    int nullableMembers = 0;
    for (PropertyColumn member : members) {
      boolean nullable = member.count() < featureCount;
      nullableMembers += nullable ? 1 : 0;
      meta.writeByte(nullable ? TYPE_STRING + 1 : TYPE_STRING);
      meta.writeString(group.suffix(member).getBytes(StandardCharsets.UTF_8));
    }

    data.writeVarint((useFsst ? 4 : 2) + members.size() + nullableMembers);
    if (useFsst) {
      writeFsstStreams(fsst, symbolLengthsStream, dictionaryLengthsStream, dictionary);
    } else {
      dictionaryLengthsStream.writeTo(data, LENGTH_DICTIONARY);
      writePlainEntries(DATA_SHARED_DICTIONARY, dictionary);
    }
    for (int m = 0; m < members.size(); m++) {
      PropertyColumn member = members.get(m);
      boolean nullable = member.count() < featureCount;
      data.writeVarint(nullable ? 2 : 1);
      if (nullable) {
        writePresent(member, featureCount);
      }
      V1IntegerStream.of(codes[m], false, false, codecs).writeTo(data, OFFSET_STRING);
    }
  }

  private V1IntegerStream symbolLengthsStream(SymbolTable fsst) {
    var symbolLengths = new LongList();
    for (int length : fsst.symbolLengths()) {
      symbolLengths.add(length);
    }
    return V1IntegerStream.of(symbolLengths, false, false, codecs);
  }

  private static long fsstStreamsSize(
      SymbolTable fsst,
      V1IntegerStream symbolLengthsStream,
      V1IntegerStream dictionaryLengthsStream,
      StringDictionary dictionary) {
    return symbolLengthsStream.size()
        + plainStreamSize(fsst.symbolLengths().length, fsst.symbols().length)
        + dictionaryLengthsStream.size()
        + plainStreamSize(dictionary.size(), fsst.compressedData().length);
  }

  private void writeFsstStreams(
      SymbolTable fsst,
      V1IntegerStream symbolLengthsStream,
      V1IntegerStream dictionaryLengthsStream,
      StringDictionary dictionary) {
    symbolLengthsStream.writeTo(data, LENGTH_SYMBOL);
    writePlainHeader(DATA_FSST, fsst.symbolLengths().length, fsst.symbols().length);
    data.writeBytes(fsst.symbols());
    dictionaryLengthsStream.writeTo(data, LENGTH_DICTIONARY);
    writePlainHeader(DATA_SINGLE_DICTIONARY, dictionary.size(), fsst.compressedData().length);
    data.writeBytes(fsst.compressedData());
  }

  private void writePlainEntries(int streamType, StringDictionary dictionary) {
    writePlainHeader(streamType, dictionary.size(), dictionary.entryBytes);
    for (byte[] utf8 : dictionary.entries) {
      data.writeBytes(utf8);
    }
  }

  private void writePresent(PropertyColumn column, int featureCount) {
    column.presence(featureCount, bits);
    booleanStream.write(data, PRESENT, bits);
  }

  private void writeInts(LongList values, int streamType) {
    V1IntegerStream.of(values, false, false, codecs).writeTo(data, streamType);
  }

  private void writePlainHeader(int streamType, int valueCount, long byteLength) {
    data.writeByte(streamType);
    data.writeByte(PLAIN_ENCODING);
    data.writeVarint(valueCount);
    data.writeVarint(byteLength);
  }

  private static long plainStreamSize(int valueCount, long byteLength) {
    return 2 + ByteWriter.varintSize(valueCount) + ByteWriter.varintSize(byteLength) + byteLength;
  }
}
