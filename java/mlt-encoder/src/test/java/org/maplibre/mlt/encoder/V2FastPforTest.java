package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import me.lemire.integercompression.Composition;
import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.VariableByte;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks the encoding decodes with JavaFastPFOR's 128-value codec, which lays blocks and exceptions
 * out the way the Rust reference decoder reads MLT v2 FastPFOR, and does not depend on earlier
 * calls.
 */
class V2FastPforTest {

  @ParameterizedTest
  @ValueSource(ints = {128, 129, 255, 256, 1000, 4096, 65535, 65536, 65537, 200_000})
  void decodes(int count) {
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      assertArrayEquals(values, decode(encode(new V2FastPfor(), values), count));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 65537})
  void reusedEncoderWritesTheSameWordsAsANewOne(int count) {
    var reused = new V2FastPfor();
    for (IntUnaryOperator generator : generators()) {
      encode(reused, generate(140_000, i -> generator.applyAsInt(i + 1) | 0x7FF));
    }
    for (IntUnaryOperator generator : generators()) {
      int[] values = generate(count, generator);
      assertArrayEquals(encode(new V2FastPfor(), values), encode(reused, values));
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

  private static int[] encode(V2FastPfor fastPfor, int[] values) {
    IntList words = fastPfor.encode(values, values.length);
    return Arrays.copyOf(words.array(), words.size());
  }

  private static int[] decode(int[] words, int count) {
    int[] decoded = new int[count + 1024];
    var outPos = new IntWrapper(0);
    new Composition(new FastPFOR128(), new VariableByte())
        .uncompress(words, new IntWrapper(0), words.length, decoded, outPos);
    return Arrays.copyOf(decoded, outPos.get());
  }
}
