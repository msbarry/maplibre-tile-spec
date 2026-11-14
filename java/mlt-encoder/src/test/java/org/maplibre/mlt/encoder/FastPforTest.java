package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.VariableByte;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compares the encoder with JavaFastPFOR, whose output v1 decoders read. */
class FastPforTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 255, 256, 257, 511, 512, 1000, 4096, 65535, 65536})
  void sameBytesAsJavaFastPfor(int count) {
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      byte[] expected = javaFastPfor(values);
      byte[] actual = encode(values);
      assertArrayEquals(expected, actual);
      assertEquals(actual.length, new FastPfor().size(values, count));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {65537, 140_000, 200_000})
  void severalPagesDecode(int count) {
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      byte[] encoded = encode(values);
      assertEquals(encoded.length, new FastPfor().size(values, count));
      // Bytes may differ from JavaFastPFOR in exception padding bits, which it fills with stale
      // values from earlier pages, so compare what decodes instead.
      assertArrayEquals(values, decode(encoded, count));
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

  private static byte[] encode(int[] values) {
    var out = new ByteWriter();
    new FastPfor().write(out, values, values.length);
    return out.toByteArray();
  }

  /** What mlt-core's encoder writes: the Composition's ints, big-endian. */
  private static byte[] javaFastPfor(int[] values) {
    int[] compressed = new int[values.length * 2 + 1024];
    var outPos = new IntWrapper(0);
    new Composition(new FastPFOR(), new VariableByte())
        .compress(values, new IntWrapper(0), values.length, compressed, outPos);
    var buffer = ByteBuffer.allocate(outPos.get() * 4);
    buffer.asIntBuffer().put(compressed, 0, outPos.get());
    return buffer.array();
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
