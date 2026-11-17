package org.maplibre.mlt.encoder;

/** The version of the MLT format to write. */
public enum MltVersion {
  /** MLT v1, the stable format every decoder reads. */
  V1,
  /**
   * MLT v2, experimental: smaller tiles, but its layout can change without a version bump, so only
   * write it where every decoder can be updated along with the encoder.
   */
  V2
}
