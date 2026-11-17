package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Runs every {@link MltEncoderTest} with FastPFOR enabled. */
class MltEncoderFastPforTest extends MltEncoderTest {

  @Override
  MltEncoder newEncoder() {
    return MltEncoder.builder().fastPfor(true).build();
  }

  @Test
  void fastPforMakesLargeLayersSmaller() {
    var plain = new MltEncoder();
    addLargeLayer(plain);
    var fastPfor = newEncoder();
    addLargeLayer(fastPfor);
    int plainSize = plain.encode().length;
    int fastPforSize = fastPfor.encode().length;
    assertTrue(fastPforSize < plainSize, fastPforSize + " vs " + plainSize);
  }
}
