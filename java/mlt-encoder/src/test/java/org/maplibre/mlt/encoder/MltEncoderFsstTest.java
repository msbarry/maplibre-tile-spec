package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Runs every {@link MltEncoderTest} with FSST enabled. */
class MltEncoderFsstTest extends MltEncoderTest {

  @Override
  MltEncoder newEncoder() {
    return MltEncoder.builder().fsst(true).build();
  }

  @Test
  void fsstMakesLargeStringColumnsSmaller() {
    var plain = new MltEncoder();
    addStreetNames(plain);
    var fsst = newEncoder();
    addStreetNames(fsst);
    int plainSize = plain.encode().length;
    int fsstSize = fsst.encode().length;
    assertTrue(fsstSize < plainSize * 0.8, fsstSize + " vs " + plainSize);
  }
}
