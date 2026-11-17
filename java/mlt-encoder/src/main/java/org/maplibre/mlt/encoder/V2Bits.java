package org.maplibre.mlt.encoder;

/**
 * MLT v2 one-bit-per-value fields (presence fields and {@code Bool} data streams) in whichever of
 * the three encodings is smallest, as the spec requires (v2 spec, "Presence Encodings").
 */
final class V2Bits {

  static final int BITMAP = 0;
  static final int RUNS = 1;
  static final int SPARSE = 2;

  private V2Bits() {}

  /** Returns the smallest encoding of {@code bits}, the lowest code on a tie. */
  static int bestEncoding(Bits bits) {
    int bitmap = size(bits, BITMAP);
    int runs = size(bits, RUNS);
    int sparse = size(bits, SPARSE);
    if (runs < bitmap && runs <= sparse) {
      return RUNS;
    }
    return sparse < bitmap && sparse < runs ? SPARSE : BITMAP;
  }

  /** The payload bytes of {@code bits} in {@code encoding}. */
  static int size(Bits bits, int encoding) {
    return switch (encoding) {
      case BITMAP -> bits.byteCount();
      case RUNS -> {
        // An empty leading run of absent values when the first value is present.
        int size = bits.size() > 0 && bits.get(0) ? 1 : 0;
        for (int start = 0; start < bits.size(); ) {
          int end = runEnd(bits, start, bits.get(start));
          size += ByteWriter.varintSize(end - start);
          start = end;
        }
        yield size;
      }
      default -> {
        int nonZero = 0;
        for (int i = 0; i < bits.byteCount(); i++) {
          nonZero += bits.bytes()[i] != 0 ? 1 : 0;
        }
        yield summaryBytes(bits) + nonZero;
      }
    };
  }

  static void writePayload(ByteWriter out, Bits bits, int encoding) {
    switch (encoding) {
      case BITMAP -> out.writeBytes(bits.bytes(), 0, bits.byteCount());
      case RUNS -> {
        // Runs alternate between absent and present, starting with absent, which may be empty.
        if (bits.size() > 0 && bits.get(0)) {
          out.writeVarint(0);
        }
        for (int start = 0; start < bits.size(); ) {
          int end = runEnd(bits, start, bits.get(start));
          out.writeVarint(end - start);
          start = end;
        }
      }
      default -> {
        byte[] bytes = bits.bytes();
        for (int summaryByte = 0; summaryByte < summaryBytes(bits); summaryByte++) {
          int summary = 0;
          for (int bit = 0; bit < 8; bit++) {
            int index = 8 * summaryByte + bit;
            if (index < bits.byteCount() && bytes[index] != 0) {
              summary |= 1 << bit;
            }
          }
          out.writeByte(summary);
        }
        for (int i = 0; i < bits.byteCount(); i++) {
          if (bytes[i] != 0) {
            out.writeByte(bytes[i]);
          }
        }
      }
    }
  }

  private static int summaryBytes(Bits bits) {
    return (bits.byteCount() + 7) / 8;
  }

  private static int runEnd(Bits bits, int start, boolean value) {
    int end = start;
    while (end < bits.size() && bits.get(end) == value) {
      end++;
    }
    return end;
  }
}
