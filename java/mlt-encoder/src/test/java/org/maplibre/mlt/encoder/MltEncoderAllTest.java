package org.maplibre.mlt.encoder;

/** Runs every {@link MltEncoderTest} with FastPFOR and FSST enabled. */
class MltEncoderAllTest extends MltEncoderTest {

  @Override
  MltEncoder newEncoder() {
    return MltEncoder.builder().fastPfor(true).fsst(true).build();
  }
}
