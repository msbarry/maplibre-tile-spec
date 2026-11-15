package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.VariableByte;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the encoding decodes with JavaFastPFOR and does not depend on earlier calls. */
class FastPforTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 255, 256, 257, 511, 512, 1000, 4096, 65535, 65536, 65537, 200_000})
  void decodes(int count) {
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      assertArrayEquals(values, decode(encode(new FastPfor(), values), count));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 1000, 65537, 200_000})
  void reusedEncoderWritesTheSameBytesAsANewOne(int count) {
    var reused = new FastPfor();
    for (IntUnaryOperator generator : generators()) {
      encode(reused, generate(140_000, i -> generator.applyAsInt(i + 1) | 0x7FF));
    }
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      assertArrayEquals(encode(new FastPfor(), values), encode(reused, values));
    }
  }

  private static IntUnaryOperator[] generators() {
    return new IntUnaryOperator[] {
      i -> 0,
      i -> 7,
      i -> i,
      i -> mix(i) & 0xF,
      // Mostly small with rare outliers of varying widths, so blocks have exceptions.
      i -> mix(i) % 50 == 0 ? mix(i + 1) >>> (mix(i + 2) & 31) : mix(i) & 0x3,
      i -> mix(i) % 9 == 0 ? 1 << 4 : 1,
      i -> mix(i),
      i -> -1
    };
  }

  private static int mix(int i) {
    int x = i * 0x9E3779B9;
    x ^= x >>> 16;
    x *= 0x85EBCA6B;
    return x ^ (x >>> 13);
  }

  private static int[] generate(int count, IntUnaryOperator generator) {
    int[] values = new int[count];
    for (int i = 0; i < count; i++) {
      values[i] = generator.applyAsInt(i);
    }
    return values;
  }

  private static byte[] encode(FastPfor fastPfor, int[] values) {
    var out = new ByteWriter();
    FastPfor.write(out, fastPfor.encode(values, values.length));
    return out.toByteArray();
  }

  private static int[] decode(byte[] encoded, int count) {
    int[] ints = new int[encoded.length / 4];
    ByteBuffer.wrap(encoded).asIntBuffer().get(ints);
    int[] decoded = new int[count + 1024];
    var outPos = new IntWrapper(0);
    new Composition(new FastPFOR(), new VariableByte())
        .uncompress(ints, new IntWrapper(0), ints.length, decoded, outPos);
    return Arrays.copyOf(decoded, outPos.get());
  }
}
