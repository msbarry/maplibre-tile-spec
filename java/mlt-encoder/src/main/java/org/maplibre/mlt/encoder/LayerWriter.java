package org.maplibre.mlt.encoder;

/** Writes layers in one version of the MLT format. */
interface LayerWriter {

  /**
   * Appends {@code layer}, which has at least one feature, to {@code tile}, and returns its size.
   */
  MltEncoder.LayerSize write(Layer layer, ByteWriter tile);
}
