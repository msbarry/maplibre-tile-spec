package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * An MLT v2 integer stream (the {@code Int} and {@code Str} families) stored with whichever
 * encoding takes the fewest bytes: none, delta, RLE or delta-RLE as VarInt words, bit-packed words,
 * fixed-width words, or, when enabled, none or delta as FastPFOR words.
 *
 * <p>The size of every candidate is computed exactly without writing it, so nothing is encoded
 * twice to compare them.
 */
final class V2IntegerStream {

  /** The logical (bits 6-4) and physical (bits 3-2) fields of the v2 encoding byte. */
  private enum Encoding {
    NONE_VARINT(0, 0b10),
    DELTA_VARINT(1, 0b10),
    RLE(2, 0),
    DELTA_RLE(3, 0),
    BIT_PACKED(4, 0),
    /** Fixed-width little-endian words, without a byte length since the count implies it. */
    NONE_FIXED(0, 0b00),
    NONE_FASTPFOR(0, 0b11),
    DELTA_FASTPFOR(1, 0b11);

    final int bits;

    Encoding(int logical, int physical) {
      this.bits = (logical << 4) | (physical << 2);
    }

    boolean isDelta() {
      return this == DELTA_VARINT || this == DELTA_RLE || this == DELTA_FASTPFOR;
    }
  }

  private final LongList values;
  private final boolean signed;
  private final boolean wide;
  private Encoding encoding;
  private int payloadBytes;
  private int bitWidth;

  /** The FastPFOR encoding to write, or null for the other encodings. */
  private int[] fastPforWords;

  /**
   * Plans the stream of {@code values}: {@code signed} values get zigzag-encoded, and {@code wide}
   * streams have 64-bit words (deltas of the others wrap at 32 bits, as decoders compute them).
   */
  static V2IntegerStream of(LongList values, boolean signed, boolean wide, Codecs codecs) {
    return new V2IntegerStream(values, signed, wide, codecs);
  }

  private V2IntegerStream(LongList values, boolean signed, boolean wide, Codecs codecs) {
    this.values = values;
    this.signed = signed;
    this.wide = wide;
    long noneBytes = 0;
    long deltaBytes = 0;
    long valueRunBytes = 0;
    long deltaRunBytes = 0;
    long maxWord = 0;
    int runStart = 0;
    int deltaRunStart = 0;
    for (int i = 0; i < values.size(); i++) {
      long word = word(i, false);
      long deltaWord = word(i, true);
      noneBytes += ByteWriter.varintSize(word);
      deltaBytes += ByteWriter.varintSize(deltaWord);
      maxWord |= word;
      if (i + 1 == values.size() || word(i + 1, false) != word) {
        valueRunBytes += ByteWriter.varintSize(i + 1 - runStart) + ByteWriter.varintSize(word);
        runStart = i + 1;
      }
      if (i + 1 == values.size() || word(i + 1, true) != deltaWord) {
        deltaRunBytes +=
            ByteWriter.varintSize(i + 1 - deltaRunStart) + ByteWriter.varintSize(deltaWord);
        deltaRunStart = i + 1;
      }
    }
    // Strictly smaller only, so ties keep the earlier encoding, which is cheaper to decode.
    consider(Encoding.NONE_VARINT, noneBytes);
    consider(Encoding.DELTA_VARINT, deltaBytes);
    consider(Encoding.RLE, valueRunBytes);
    consider(Encoding.DELTA_RLE, deltaRunBytes);
    if (!wide) {
      // Bit packing needs the words in 32 bits, the width of the widest, at least 1.
      int width = Math.max(1, 64 - Long.numberOfLeadingZeros(maxWord));
      if (consider(Encoding.BIT_PACKED, 1 + ((long) values.size() * width + 7) / 8)) {
        bitWidth = width;
      }
    }
    consider(Encoding.NONE_FIXED, (long) values.size() * (wide ? 8 : 4));
    // FastPFOR needs the words as 32-bit ints, so only materialize them where it can win.
    if (codecs.fastPforEnabled() && !wide && values.size() >= V2FastPfor.MIN_VALUES) {
      considerFastPfor(Encoding.NONE_FASTPFOR, codecs.v2FastPfor());
      considerFastPfor(Encoding.DELTA_FASTPFOR, codecs.v2FastPfor());
    }
  }

  /** Switches to {@code candidate} if it is smaller, and returns whether it did. */
  private boolean consider(Encoding candidate, long candidatePayloadBytes) {
    if (encoding == null
        || streamBytes(candidate, candidatePayloadBytes) < streamBytes(encoding, payloadBytes)) {
      encoding = candidate;
      payloadBytes = (int) candidatePayloadBytes;
      fastPforWords = null;
      return true;
    }
    return false;
  }

  private void considerFastPfor(Encoding candidate, V2FastPfor fastPfor) {
    int[] words = new int[values.size()];
    for (int i = 0; i < words.length; i++) {
      words[i] = (int) word(i, candidate.isDelta());
    }
    IntList encoded = fastPfor.encode(words, words.length);
    if (consider(candidate, 4L * encoded.size())) {
      fastPforWords = Arrays.copyOf(encoded.array(), encoded.size());
    }
  }

  /** The bytes the stream takes besides its value count, which only depends on the context. */
  private static long streamBytes(Encoding encoding, long payloadBytes) {
    long byteLength = encoding == Encoding.NONE_FIXED ? 0 : ByteWriter.varintSize(payloadBytes);
    return byteLength + payloadBytes;
  }

  /**
   * Returns the bytes {@link #writeTo} writes, given the value count a decoder infers from the
   * stream's place (v2 spec, "Value Count").
   */
  int size(int impliedCount) {
    return 1 + countBytes(impliedCount) + (int) streamBytes(encoding, payloadBytes);
  }

  private int countBytes(int impliedCount) {
    return values.size() == impliedCount ? 0 : ByteWriter.varintSize(values.size());
  }

  /**
   * Writes the stream. {@code extension} goes in the low 2 bits of the encoding byte: the layout of
   * a string column's leading stream, otherwise 0.
   */
  void writeTo(ByteWriter out, int impliedCount, int extension) {
    int count = values.size();
    boolean explicitCount = count != impliedCount;
    out.writeByte((explicitCount ? 0x80 : 0) | encoding.bits | extension);
    if (explicitCount) {
      out.writeVarint(count);
    }
    if (encoding != Encoding.NONE_FIXED) {
      out.writeVarint(payloadBytes);
    }
    switch (encoding) {
      case NONE_VARINT, DELTA_VARINT -> {
        for (int i = 0; i < count; i++) {
          out.writeVarint(word(i, encoding.isDelta()));
        }
      }
      case RLE, DELTA_RLE -> writeRuns(out, encoding.isDelta());
      case BIT_PACKED -> writeBitPacked(out);
      case NONE_FIXED -> {
        for (int i = 0; i < count; i++) {
          if (wide) {
            out.writeLongLE(word(i, false));
          } else {
            out.writeIntLE((int) word(i, false));
          }
        }
      }
      case NONE_FASTPFOR, DELTA_FASTPFOR -> {
        for (int word : fastPforWords) {
          out.writeIntLE(word);
        }
      }
    }
  }

  /** Writes {@code (run length, word)} pairs, as v2 RLE lays them out. */
  private void writeRuns(ByteWriter out, boolean delta) {
    int count = values.size();
    int runStart = 0;
    for (int i = 1; i <= count; i++) {
      if (i == count || word(i, delta) != word(i - 1, delta)) {
        out.writeVarint(i - runStart);
        out.writeVarint(word(i - 1, delta));
        runStart = i;
      }
    }
  }

  /** Writes the width, then every word in that many bits, LSB-first. */
  private void writeBitPacked(ByteWriter out) {
    out.writeByte(bitWidth);
    long buffer = 0;
    int bufferBits = 0;
    for (int i = 0; i < values.size(); i++) {
      buffer |= word(i, false) << bufferBits;
      bufferBits += bitWidth;
      while (bufferBits >= 8) {
        out.writeByte((int) buffer);
        buffer >>>= 8;
        bufferBits -= 8;
      }
    }
    if (bufferBits > 0) {
      out.writeByte((int) buffer);
    }
  }

  private long word(int index, boolean delta) {
    long value = values.get(index);
    if (!delta) {
      return signed ? zigzag(value) : value;
    }
    long difference = value - (index == 0 ? 0 : values.get(index - 1));
    return wide ? zigzag(difference) : Integer.toUnsignedLong(zigzag32((int) difference));
  }

  private static long zigzag(long value) {
    return (value << 1) ^ (value >> 63);
  }

  private static int zigzag32(int value) {
    return (value << 1) ^ (value >> 31);
  }
}
