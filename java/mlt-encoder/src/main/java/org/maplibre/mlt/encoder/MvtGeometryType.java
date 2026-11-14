package org.maplibre.mlt.encoder;

/**
 * The geometry type of an MVT feature, which says how to read its command stream. Features with
 * several points, lines or polygons become the matching MLT {@code Multi*} type.
 */
public enum MvtGeometryType {
  /** One or more points: {@code MoveTo} commands only. */
  POINT,
  /** One or more lines: each a {@code MoveTo}, then {@code LineTo}s. */
  LINESTRING,
  /**
   * One or more polygons: rings of a {@code MoveTo}, {@code LineTo}s and a {@code ClosePath}, each
   * positive-area ring starting a polygon and each other ring a hole in it.
   */
  POLYGON
}
