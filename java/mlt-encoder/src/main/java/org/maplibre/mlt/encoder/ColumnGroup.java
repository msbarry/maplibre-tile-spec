package org.maplibre.mlt.encoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One column as written: a property column, or a shared dictionary and the string columns that
 * index into it.
 *
 * @param prefix for a shared dictionary, the start of every member's name, which MLT stores once;
 *     {@code null} for a lone column
 * @param members the columns, in layer order; exactly one for a lone column
 */
record ColumnGroup(String prefix, List<PropertyColumn> members) {

  boolean isSharedDictionary() {
    return prefix != null;
  }

  /** The member's name without the shared prefix, as MLT stores it. */
  String suffix(PropertyColumn member) {
    return member.name.substring(prefix.length());
  }

  /**
   * Groups {@code columns} as {@code sharing} says. Each shared dictionary takes the place of its
   * first member, so the columns stay in layer order otherwise.
   */
  static List<ColumnGroup> of(List<PropertyColumn> columns, SharedDictionaries sharing) {
    Map<Object, List<PropertyColumn>> byGroup = new LinkedHashMap<>();
    for (PropertyColumn column : columns) {
      String group =
          column.valueType() == PropertyColumn.ValueType.STRING
              ? sharing.groupOf(column.name)
              : null;
      // A column with no group is a group of its own, keyed by itself.
      byGroup.computeIfAbsent(group != null ? group : column, key -> new ArrayList<>()).add(column);
    }
    List<ColumnGroup> result = new ArrayList<>(byGroup.size());
    for (List<PropertyColumn> members : byGroup.values()) {
      result.add(
          members.size() == 1
              ? new ColumnGroup(null, members)
              : new ColumnGroup(commonPrefix(members), members));
    }
    return result;
  }

  private static String commonPrefix(List<PropertyColumn> members) {
    String prefix = members.getFirst().name;
    for (PropertyColumn member : members) {
      int length = 0;
      while (length < prefix.length()
          && length < member.name.length()
          && prefix.charAt(length) == member.name.charAt(length)) {
        length++;
      }
      prefix = prefix.substring(0, length);
    }
    // Never split a surrogate pair, so the prefix and every suffix stay valid UTF-8.
    if (!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length() - 1))) {
      prefix = prefix.substring(0, prefix.length() - 1);
    }
    return prefix;
  }
}
