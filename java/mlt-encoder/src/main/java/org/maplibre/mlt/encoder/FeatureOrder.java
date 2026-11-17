package org.maplibre.mlt.encoder;

/**
 * An order to write the features of a layer in. Grouping similar features makes runs and deltas
 * longer, so a layer can get smaller, but the order also changes which features renderers draw on
 * top, so only reorder layers where that does not matter.
 */
public enum FeatureOrder {
  /** The order features were added in. */
  INPUT,
  /** By feature id, unsigned, with features without an id last. */
  ID,
  /** By the Hilbert curve index of each feature's first vertex: neighbours stay close. */
  HILBERT,
  /** By the Z-order (Morton) curve index of each feature's first vertex: cheaper than Hilbert. */
  MORTON
}
