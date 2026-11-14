package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FsstTest {

  private final Fsst fsst = new Fsst();

  @Test
  void roundTrips() {
    for (String text :
        new String[] {
          "a",
          "ab",
          "abcabcabcabcabcabcabcabcabc",
          "Main Street, Main Street North, Maple Avenue, Main Street South",
          "Zürich Genève Köln Kraków 東京 Москва Zürich Genève Köln Kraków 東京 Москва",
          repeat("residential commercial industrial ", 2000),
          numbers(50_000)
        }) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(
          bytes, decode(compress(bytes)), text.substring(0, Math.min(40, text.length())));
    }
  }

  @Test
  void everyByteValue() {
    byte[] bytes = new byte[5000];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31 + (i >> 8));
    }
    assertArrayEquals(bytes, decode(compress(bytes)));
  }

  @Test
  void compressesRepetitiveText() {
    byte[] bytes = repeat("Massachusetts Avenue ", 500).getBytes(StandardCharsets.UTF_8);
    compress(bytes);
    int size =
        fsst.compressed.size() + fsst.trainedSymbols.size() + fsst.trainedSymbolLengths.size();
    assertTrue(size < bytes.length / 4, size + " of " + bytes.length);
  }

  @Test
  void reusableAcrossCalls() {
    byte[] first = repeat("first text ", 100).getBytes(StandardCharsets.UTF_8);
    byte[] second = numbers(3000).getBytes(StandardCharsets.UTF_8);
    compress(first);
    byte[] firstCompressed = fsst.compressed.toByteArray();
    assertArrayEquals(second, decode(compress(second)));
    compress(first);
    assertArrayEquals(firstCompressed, fsst.compressed.toByteArray());
  }

  /** Compresses {@code bytes} as values of up to 20 bytes. */
  private Fsst compress(byte[] bytes) {
    var valueLengths = new LongList();
    for (int start = 0; start < bytes.length; start += 20) {
      valueLengths.add(Math.min(20, bytes.length - start));
    }
    fsst.compress(Arrays.copyOf(bytes, bytes.length + Fsst.PADDING), valueLengths);
    return fsst;
  }

  /** Decodes as the spec describes: codes expand to symbols, 255 escapes the next byte. */
  private static byte[] decode(Fsst fsst) {
    byte[] symbols = fsst.trainedSymbols.toByteArray();
    int[] offsets = new int[fsst.trainedSymbolLengths.size() + 1];
    for (int i = 0; i < fsst.trainedSymbolLengths.size(); i++) {
      offsets[i + 1] = offsets[i] + (int) fsst.trainedSymbolLengths.get(i);
    }
    byte[] corpus = fsst.compressed.toByteArray();
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < corpus.length; i++) {
      int code = corpus[i] & 0xFF;
      if (code == 255) {
        out.write(corpus[++i]);
      } else {
        out.write(symbols, offsets[code], offsets[code + 1] - offsets[code]);
      }
    }
    return out.toByteArray();
  }

  private static String repeat(String text, int times) {
    return text.repeat(times);
  }

  private static String numbers(int count) {
    var result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      result.append(i * 7919 % 100_003).append(i % 3 == 0 ? " Street" : " Road");
    }
    return result.toString();
  }
}
