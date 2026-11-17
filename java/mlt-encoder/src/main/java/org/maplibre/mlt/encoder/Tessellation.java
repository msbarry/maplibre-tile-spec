package org.maplibre.mlt.encoder;

/**
 * Whether to store polygons pre-cut into triangles, so renderers can upload them to the GPU without
 * triangulating them first, at the cost of bigger tiles.
 */
public enum Tessellation {
  /** Store polygons as rings only. */
  NONE,
  /** Store the triangles next to the polygon rings. */
  WITH_OUTLINES,
  /**
   * In v2, store only the triangles for layers where every feature is a polygon: smaller, but
   * renderers can no longer stroke the rings. Other layers, and every v1 layer, keep their rings.
   */
  TRIANGLES_ONLY
}
