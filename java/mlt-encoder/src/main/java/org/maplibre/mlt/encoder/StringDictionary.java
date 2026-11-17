package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.maplibre.mlt.converter.encodings.fsst.SymbolTable;

/**
 * The distinct values of one string column, or of the columns of a shared dictionary, in the order
 * they first appear, and the code (index in the dictionary) of every value.
 */
final class StringDictionary {

  /**
   * FSST is only tried on dictionaries of at least this many bytes, as the Rust encoder does:
   * training on smaller ones costs much more time than the few bytes it saves.
   */
  static final int MIN_FSST_BYTES = 2048;

  private final Map<String, Integer> codesByValue = new HashMap<>();
  final List<byte[]> entries = new ArrayList<>();
  final LongList entryLengths = new LongList();
  long entryBytes;

  /** Returns a dictionary of the values of {@code column}, with their codes in {@code codes}. */
  static StringDictionary of(PropertyColumn column, LongList codes) {
    var dictionary = new StringDictionary();
    dictionary.addAll(column, codes);
    return dictionary;
  }

  /** Adds the values of {@code column} and appends their codes to {@code codes}. */
  void addAll(PropertyColumn column, LongList codes) {
    codes.clear();
    for (int i = 0; i < column.count(); i++) {
      codes.add(add(column.stringValue(i)));
    }
  }

  private int add(String value) {
    Integer code = codesByValue.get(value);
    if (code == null) {
      code = entries.size();
      codesByValue.put(value, code);
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      entries.add(utf8);
      entryLengths.add(utf8.length);
      entryBytes += utf8.length;
    }
    return code;
  }

  int size() {
    return entries.size();
  }

  /**
   * Returns the entries compressed with FSST, or {@code null} if FSST is disabled or the dictionary
   * is too small for it to pay off.
   */
  SymbolTable compress(Codecs codecs) {
    if (!codecs.fsstEnabled() || entryBytes < MIN_FSST_BYTES) {
      return null;
    }
    byte[] text = new byte[(int) entryBytes];
    int position = 0;
    for (byte[] utf8 : entries) {
      System.arraycopy(utf8, 0, text, position, utf8.length);
      position += utf8.length;
    }
    return codecs.fsst().encode(text);
  }
}
