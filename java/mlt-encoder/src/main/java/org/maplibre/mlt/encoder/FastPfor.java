package org.maplibre.mlt.encoder;

import java.nio.ByteBuffer;
import java.util.Arrays;
import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;
import me.lemire.integercompression.VariableByte;

/**
 * The v1 SIMD-FastPFOR physical encoding of 32-bit words: JavaFastPFOR's {@code
 * Composition(FastPFOR, VariableByte)} output, with every 32-bit word stored big-endian. Not
 * thread-safe: the codec holds scratch arrays.
 */
final class FastPfor {

  /** Below this many values there is no whole block, so VarInt words are always smaller. */
  static final int MIN_VALUES = 256;

  private static final int PAGE = 65536;

  // JavaFastPFOR allocates direct buffers by default: VariableByte one per call, which the JDK
  // frees through a global lock that serializes encoders at high thread counts.
  private final IntegerCODEC codec =
      new Composition(
          new FastPFOR() {
            @Override
            protected ByteBuffer makeBuffer(int sizeInBytes) {
              return ByteBuffer.allocate(sizeInBytes);
            }
          },
          new VariableByte() {
            @Override
            protected ByteBuffer makeBuffer(int sizeInBytes) {
              return ByteBuffer.allocate(sizeInBytes);
            }
          });

  private int[] encoded = new int[1024];

  /** Returns the encoding of the first {@code count} words, as 32-bit words. */
  int[] encode(int[] words, int count) {
    // Bit packing never takes more words than the input, plus metadata and the varint tail.
    int capacity = 2 * count + 1024;
    if (encoded.length < capacity) {
      encoded = new int[capacity];
    }
    var outputPosition = new IntWrapper(0);
    codec.compress(words, new IntWrapper(0), count, encoded, outputPosition);
    clearExceptionPadding(count);
    return Arrays.copyOf(encoded, outputPosition.get());
  }

  /**
   * Zeroes the unused high bits of the last word of each exception array. JavaFastPFOR packs
   * exceptions 32 at a time and fills those bits with values left over from earlier calls, so
   * without this the output of a reused codec would depend on what it encoded before.
   */
  private void clearExceptionPadding(int count) {
    int blockValues = count - count % MIN_VALUES;
    // Skip FastPFOR's value count, then walk each page: its offset to the metadata, the packed
    // blocks, the metadata byte count and bytes, a bitmap of exception widths, then per width the
    // exception count and packed exceptions.
    int position = 1;
    for (int pageStart = 0; pageStart < blockValues; pageStart += PAGE) {
      position += encoded[position];
      int metadataBytes = encoded[position++];
      position += (metadataBytes + 3) / 4;
      int widths = encoded[position++];
      for (int width = 2; width <= 32; width++) {
        if ((widths & (1 << (width - 1))) != 0) {
          long bits = (long) encoded[position++] * width;
          position += (int) ((bits + 31) / 32);
          int usedBitsInLastWord = (int) (bits % 32);
          if (usedBitsInLastWord != 0) {
            encoded[position - 1] &= (1 << usedBitsInLastWord) - 1;
          }
        }
      }
    }
  }

  static void write(ByteWriter out, int[] encoded) {
    for (int word : encoded) {
      out.writeIntBE(word);
    }
  }
}
