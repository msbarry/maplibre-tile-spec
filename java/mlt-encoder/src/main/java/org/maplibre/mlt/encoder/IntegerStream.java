package org.maplibre.mlt.encoder;

/**
 * An integer stream stored with whichever v1 encoding takes the fewest bytes: none, delta, RLE or
 * delta-RLE as VarInt words, or, when enabled, none or delta as FastPFOR words.
 *
 * <p>The size of every candidate is computed exactly without writing it, so nothing is encoded
 * twice to compare them.
 */
final class IntegerStream {

  private static final int VARINT = 0b10;
  private static final int FASTPFOR = 0b01;

  /** The logical bits of the v1 encoding byte: logical1 (bits 7-5) and logical2 (4-2). */
  private enum Logical {
    NONE(0b000_000_00),
    DELTA(0b001_000_00),
    RLE(0b011_000_00),
    DELTA_RLE(0b001_011_00);

    final int bits;

    Logical(int bits) {
      this.bits = bits;
    }

    boolean isDelta() {
      return this == DELTA || this == DELTA_RLE;
    }

    boolean isRle() {
      return this == RLE || this == DELTA_RLE;
    }
  }

  private final LongList values;
  private final boolean signed;
  private final boolean wide;
  private final FastPfor fastPfor;
  private Logical logical;
  private int payloadBytes;
  private int runs;

  /** The FastPFOR encoding to write, or null to write VarInt words. */
  private int[] fastPforWords;

  /**
   * Plans the stream of {@code values}: {@code signed} values get zigzag-encoded, and {@code wide}
   * streams have 64-bit words (deltas of the others wrap at 32 bits, as decoders compute them).
   */
  static IntegerStream of(LongList values, boolean signed, boolean wide, Codecs codecs) {
    return new IntegerStream(values, signed, wide, codecs.fastPfor);
  }

  private IntegerStream(LongList values, boolean signed, boolean wide, FastPfor fastPfor) {
    this.fastPfor = fastPfor;
    this.values = values;
    this.signed = signed;
    this.wide = wide;
    long noneBytes = 0;
    long deltaBytes = 0;
    var valueRuns = new RunCounter();
    var deltaRuns = new RunCounter();
    long previous = 0;
    for (int i = 0; i < values.size(); i++) {
      long value = values.get(i);
      long word = valueWord(value);
      long deltaWord = deltaWord(value, previous);
      noneBytes += ByteWriter.varintSize(word);
      deltaBytes += ByteWriter.varintSize(deltaWord);
      valueRuns.add(word);
      deltaRuns.add(deltaWord);
      previous = value;
    }
    // Strictly smaller only, so ties keep the earlier encoding, which is cheaper to decode.
    consider(Logical.NONE, noneBytes, 0);
    consider(Logical.DELTA, deltaBytes, 0);
    consider(Logical.RLE, valueRuns.payloadBytes(), valueRuns.runs);
    consider(Logical.DELTA_RLE, deltaRuns.payloadBytes(), deltaRuns.runs);
    // FastPFOR needs the words as 32-bit ints, so only materialize them where it can win.
    if (fastPfor != null && !wide && values.size() >= FastPfor.MIN_VALUES) {
      considerFastPfor(Logical.NONE);
      considerFastPfor(Logical.DELTA);
    }
  }

  private void consider(Logical candidate, long candidatePayloadBytes, int candidateRuns) {
    int count = values.size();
    if (logical == null
        || streamSize(candidate, (int) candidatePayloadBytes, candidateRuns, count)
            < streamSize(logical, payloadBytes, runs, count)) {
      logical = candidate;
      payloadBytes = (int) candidatePayloadBytes;
      runs = candidateRuns;
      fastPforWords = null;
    }
  }

  private void considerFastPfor(Logical candidate) {
    int[] words = new int[values.size()];
    for (int i = 0; i < words.length; i++) {
      words[i] = (int) word(i, candidate.isDelta());
    }
    int[] encoded = fastPfor.encode(words, words.length);
    int bytes = 4 * encoded.length;
    if (streamSize(candidate, bytes, 0, words.length) < size()) {
      logical = candidate;
      payloadBytes = bytes;
      runs = 0;
      fastPforWords = encoded;
    }
  }

  /** Returns the bytes {@link #writeTo} writes. */
  int size() {
    return streamSize(logical, payloadBytes, runs, values.size());
  }

  void writeTo(ByteWriter out, int streamType) {
    int count = values.size();
    out.writeByte(streamType);
    out.writeByte(logical.bits | (fastPforWords != null ? FASTPFOR : VARINT));
    if (fastPforWords != null) {
      out.writeVarint(count);
      out.writeVarint(payloadBytes);
      FastPfor.write(out, fastPforWords);
    } else if (logical.isRle()) {
      out.writeVarint(2L * runs);
      out.writeVarint(payloadBytes);
      out.writeVarint(runs);
      out.writeVarint(count);
      writeRuns(out, logical.isDelta());
    } else {
      out.writeVarint(count);
      out.writeVarint(payloadBytes);
      long previous = 0;
      for (int i = 0; i < count; i++) {
        long value = values.get(i);
        out.writeVarint(logical.isDelta() ? deltaWord(value, previous) : valueWord(value));
        previous = value;
      }
    }
  }

  /** Writes every run length, then the word of every run, as v1 RLE lays them out. */
  private void writeRuns(ByteWriter out, boolean delta) {
    int count = values.size();
    int runStart = 0;
    for (int i = 1; i <= count; i++) {
      if (i == count || word(i, delta) != word(i - 1, delta)) {
        out.writeVarint(i - runStart);
        runStart = i;
      }
    }
    for (int i = 0; i < count; i++) {
      if (i == 0 || word(i, delta) != word(i - 1, delta)) {
        out.writeVarint(word(i, delta));
      }
    }
  }

  private long word(int index, boolean delta) {
    long value = values.get(index);
    return delta ? deltaWord(value, index == 0 ? 0 : values.get(index - 1)) : valueWord(value);
  }

  private long valueWord(long value) {
    return signed ? zigzag(value) : value;
  }

  private long deltaWord(long value, long previous) {
    long delta = value - previous;
    return zigzag(wide ? delta : (int) delta);
  }

  /** Counts the runs of equal words and the bytes their RLE payload takes. */
  private static final class RunCounter {
    int runs;
    long closedRunBytes;
    long word;
    int length;

    void add(long next) {
      if (length > 0 && next == word) {
        length++;
      } else {
        closedRunBytes += runBytes();
        runs++;
        word = next;
        length = 1;
      }
    }

    long payloadBytes() {
      return closedRunBytes + runBytes();
    }

    private long runBytes() {
      return length == 0 ? 0 : ByteWriter.varintSize(length) + ByteWriter.varintSize(word);
    }
  }

  private static long zigzag(long value) {
    return (value << 1) ^ (value >> 63);
  }

  private static int streamSize(Logical logical, int payloadBytes, int runs, int count) {
    int header = 2 + ByteWriter.varintSize(payloadBytes);
    if (logical.isRle()) {
      header +=
          ByteWriter.varintSize(2L * runs)
              + ByteWriter.varintSize(runs)
              + ByteWriter.varintSize(count);
    } else {
      header += ByteWriter.varintSize(count);
    }
    return header + payloadBytes;
  }
}
