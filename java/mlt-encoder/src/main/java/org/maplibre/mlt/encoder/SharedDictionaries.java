package org.maplibre.mlt.encoder;

/**
 * Which string columns of a layer share one dictionary. Columns like {@code name}, {@code name:en}
 * and {@code name:de} often hold the same values, and a shared dictionary stores each value once
 * for all of them.
 *
 * {@snippet :
 * // Every string column of a layer in one dictionary:
 * SharedDictionaries perLayer = SharedDictionaries.PER_LAYER;
 * // Only the name columns:
 * SharedDictionaries names = column -> column.startsWith("name") ? "name" : null;
 * }
 */
@FunctionalInterface
public interface SharedDictionaries {

  /** Every column keeps its own dictionary. */
  SharedDictionaries NONE = column -> null;

  /** All string columns of a layer share one dictionary. */
  SharedDictionaries PER_LAYER = column -> "";

  /**
   * Returns the group of the string column named {@code column}: string columns of a layer with
   * equal groups share one dictionary, and a column whose group is {@code null}, or the only one in
   * its group, keeps its own. Columns of other types are never grouped.
   */
  String groupOf(String column);
}
