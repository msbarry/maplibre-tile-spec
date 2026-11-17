package org.maplibre.mlt.encoder;

/**
 * Writes MLT v1 boolean streams ({@code Present} streams and {@code Bool} column data): the bitmap
 * compressed with ORC byte run-length encoding.
 */
final class V1BooleanStream {

  private static final int RLE_ENCODING = 0b011_000_00;
  private static final int MIN_REPEAT = 3;
  private static final int MAX_REPEAT = 130;
  private static final int MAX_LITERALS = 128;

  private final ByteWriter payload = new ByteWriter();

  void write(ByteWriter out, int streamType, Bits bits) {
    payload.clear();
    byte[] bitmap = bits.bytes();
    int byteCount = bits.byteCount();
    int literalStart = 0;
    int i = 0;
    while (i < byteCount) {
      int runEnd = i + 1;
      while (runEnd < byteCount && runEnd - i < MAX_REPEAT && bitmap[runEnd] == bitmap[i]) {
        runEnd++;
      }
      if (runEnd - i >= MIN_REPEAT) {
        writeLiterals(bitmap, literalStart, i);
        payload.writeByte(runEnd - i - MIN_REPEAT);
        payload.writeByte(bitmap[i]);
        literalStart = runEnd;
      }
      i = runEnd;
    }
    writeLiterals(bitmap, literalStart, byteCount);

    out.writeByte(streamType);
    out.writeByte(RLE_ENCODING);
    out.writeVarint(bits.size());
    out.writeVarint(payload.size());
    out.writeBytes(payload);
  }

  private void writeLiterals(byte[] bitmap, int start, int end) {
    for (int chunk = start; chunk < end; chunk += MAX_LITERALS) {
      int length = Math.min(MAX_LITERALS, end - chunk);
      payload.writeByte(256 - length);
      payload.writeBytes(bitmap, chunk, length);
    }
  }
}
