package org.maplibre.mlt.encoder;

import java.nio.ByteBuffer;
import java.util.Arrays;
import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;
import me.lemire.integercompression.VariableByte;

/**
 * The MLT v1 FastPFOR physical encoding of 32-bit words: JavaFastPFOR's {@code
 * Composition(FastPFOR, VariableByte)} output, with every 32-bit word stored big-endian. Not
 * thread-safe: the codec holds scratch arrays.
 */
final class V1FastPfor {

  /** Below this many values there is no whole block, so VarInt words are always smaller. */
  static final int MIN_VALUES = 256;

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
    return Arrays.copyOf(encoded, outputPosition.get());
  }

  static void write(ByteWriter out, int[] encoded) {
    for (int word : encoded) {
      out.writeIntBE(word);
    }
  }
}
