package org.maplibre.mlt.encoder;

import org.maplibre.mlt.converter.encodings.fsst.SymbolTableBuilder;

/**
 * The optional codecs an encoder may use, and scratch buffers that columns use while they are
 * written, shared so each tile reuses them.
 */
final class Codecs {

  /** FastPFOR, or null when it is disabled. */
  final FastPfor fastPfor;

  /** FSST, or null when it is disabled. */
  final SymbolTableBuilder fsst;

  final BooleanStream booleans = new BooleanStream();
  private final LongList[] longs = {new LongList(), new LongList(), new LongList(), new LongList()};

  Codecs(boolean fastPfor, boolean fsst) {
    this.fastPfor = fastPfor ? new FastPfor() : null;
    this.fsst = fsst ? new SymbolTableBuilder() : null;
  }

  /** Returns scratch list {@code index}, emptied. */
  LongList longs(int index) {
    LongList list = longs[index];
    list.clear();
    return list;
  }
}
