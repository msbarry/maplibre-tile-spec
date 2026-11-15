package org.maplibre.mlt.converter.encodings.fsst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SymbolTableBuilderTest {

  private final SymbolTableBuilder builder = new SymbolTableBuilder();

  @Test
  void roundTrips() {
    for (String text :
        new String[] {
          "",
          "a",
          "ab",
          "abcabcabcabcabcabcabcabcabc",
          "Main Street, Main Street North, Maple Avenue, Main Street South",
          "Zürich Genève Köln Kraków 東京 Москва Zürich Genève Köln Kraków 東京 Москва",
          "residential commercial industrial ".repeat(2000),
          numbers(50_000)
        }) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(
          bytes, decode(builder.encode(bytes)), text.substring(0, Math.min(40, text.length())));
    }
  }

  @Test
  void everyByteValue() {
    byte[] bytes = new byte[5000];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31 + (i >> 8));
    }
    assertArrayEquals(bytes, decode(builder.encode(bytes)));
  }

  @Test
  void compressesRepetitiveText() {
    byte[] bytes = "Massachusetts Avenue ".repeat(500).getBytes(StandardCharsets.UTF_8);
    int size = builder.encode(bytes).weight();
    assertTrue(size < bytes.length / 4, size + " of " + bytes.length);
  }

  @Test
  void reusableAcrossCalls() {
    byte[] first = "first text ".repeat(100).getBytes(StandardCharsets.UTF_8);
    byte[] second = numbers(3000).getBytes(StandardCharsets.UTF_8);
    SymbolTable firstTable = builder.encode(first);
    assertArrayEquals(second, decode(builder.encode(second)));
    assertEquals(firstTable, builder.encode(first));
  }

  private static byte[] decode(SymbolTable table) {
    return new FsstJava().decode(table);
  }

  private static String numbers(int count) {
    var result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      result.append(i * 7919 % 100_003).append(i % 3 == 0 ? " Street" : " Road");
    }
    return result.toString();
  }
}
