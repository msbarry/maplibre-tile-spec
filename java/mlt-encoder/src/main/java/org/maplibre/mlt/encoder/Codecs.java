package org.maplibre.mlt.encoder;

/**
 * The optional codecs an encoder may use, and scratch buffers that columns use while they are
 * written, shared so each tile reuses them.
 */
final class Codecs {

  /** FastPFOR, or null when it is disabled. */
  final FastPfor fastPfor;

  /** FSST, or null when it is disabled. */
  final Fsst fsst;

  final BooleanStream booleans = new BooleanStream();
  private final LongList[] longs = {new LongList(), new LongList(), new LongList()};

  private byte[] text = new byte[1024];

  Codecs(boolean fastPfor, boolean fsst) {
    this.fastPfor = fastPfor ? new FastPfor() : null;
    this.fsst = fsst ? new Fsst() : null;
  }

  /** Returns a scratch byte array of at least {@code length} bytes. */
  byte[] text(int length) {
    if (text.length < length) {
      text = new byte[Math.max(length, 2 * text.length)];
    }
    return text;
  }

  /** Returns scratch list {@code index}, emptied. */
  LongList longs(int index) {
    LongList list = longs[index];
    list.clear();
    return list;
  }
}
