package org.maplibre.mlt.encoder;

import org.maplibre.mlt.converter.encodings.fsst.SymbolTableBuilder;

/**
 * The optional codecs an encoder may use. Each holds tables of about 1 MB or more, so it is only
 * created the first time a stream is large enough to try it, and then reused for every tile.
 */
final class Codecs {

  private final boolean fastPforEnabled;
  private final boolean fsstEnabled;
  private V1FastPfor v1FastPfor;
  private V2FastPfor v2FastPfor;
  private SymbolTableBuilder fsst;

  Codecs(boolean fastPforEnabled, boolean fsstEnabled) {
    this.fastPforEnabled = fastPforEnabled;
    this.fsstEnabled = fsstEnabled;
  }

  boolean fastPforEnabled() {
    return fastPforEnabled;
  }

  V1FastPfor v1FastPfor() {
    if (v1FastPfor == null) {
      v1FastPfor = new V1FastPfor();
    }
    return v1FastPfor;
  }

  V2FastPfor v2FastPfor() {
    if (v2FastPfor == null) {
      v2FastPfor = new V2FastPfor();
    }
    return v2FastPfor;
  }

  boolean fsstEnabled() {
    return fsstEnabled;
  }

  SymbolTableBuilder fsst() {
    if (fsst == null) {
      fsst = new SymbolTableBuilder();
    }
    return fsst;
  }
}
