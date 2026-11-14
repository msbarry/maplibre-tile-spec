package org.maplibre.mlt.encoder;

/** Runs every {@link MltEncoderTest} with FastPFOR and FSST enabled. */
class MltEncoderAllTest extends MltEncoderTest {

  @Override
  MltEncoder newEncoder() {
    return new MltEncoder(MltEncoder.OptionalEncoding.FASTPFOR, MltEncoder.OptionalEncoding.FSST);
  }
}
