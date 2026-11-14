package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * Writes v1 boolean streams ({@code Present} streams and {@code Bool} column data): an LSB-first
 * bitmap compressed with ORC byte run-length encoding.
 */
final class BooleanStream {

  private static final int RLE_ENCODING = 0b011_000_00;
  private static final int MIN_REPEAT = 3;
  private static final int MAX_REPEAT = 130;
  private static final int MAX_LITERALS = 128;

  private byte[] bitmap = new byte[64];
  private int bitCount;
  private final ByteWriter payload = new ByteWriter();

  void clear() {
    Arrays.fill(bitmap, 0, (bitCount + 7) >>> 3, (byte) 0);
    bitCount = 0;
  }

  void add(boolean bit) {
    int byteIndex = bitCount >>> 3;
    if (byteIndex == bitmap.length) {
      bitmap = Arrays.copyOf(bitmap, bitmap.length * 2);
    }
    if (bit) {
      bitmap[byteIndex] |= (byte) (1 << (bitCount & 7));
    }
    bitCount++;
  }

  void writeTo(ByteWriter out, int streamType) {
    payload.clear();
    int byteCount = (bitCount + 7) >>> 3;
    int literalStart = 0;
    int i = 0;
    while (i < byteCount) {
      int runEnd = i + 1;
      while (runEnd < byteCount && runEnd - i < MAX_REPEAT && bitmap[runEnd] == bitmap[i]) {
        runEnd++;
      }
      if (runEnd - i >= MIN_REPEAT) {
        writeLiterals(literalStart, i);
        payload.writeByte(runEnd - i - MIN_REPEAT);
        payload.writeByte(bitmap[i]);
        literalStart = runEnd;
      }
      i = runEnd;
    }
    writeLiterals(literalStart, byteCount);

    out.writeByte(streamType);
    out.writeByte(RLE_ENCODING);
    out.writeVarint(bitCount);
    out.writeVarint(payload.size());
    out.writeBytes(payload);
  }

  private void writeLiterals(int start, int end) {
    for (int chunk = start; chunk < end; chunk += MAX_LITERALS) {
      int length = Math.min(MAX_LITERALS, end - chunk);
      payload.writeByte(256 - length);
      payload.writeBytes(bitmap, chunk, length);
    }
  }
}
