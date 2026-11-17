package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * A growable LSB-first bitmap (bit {@code i % 8} of byte {@code i / 8} is bit {@code i}), the form
 * every MLT version starts from for presence and boolean streams.
 */
final class Bits {

  private byte[] bytes = new byte[64];
  private int size;

  void clear() {
    Arrays.fill(bytes, 0, byteCount(), (byte) 0);
    size = 0;
  }

  void add(boolean bit) {
    int byteIndex = size >>> 3;
    if (byteIndex == bytes.length) {
      bytes = Arrays.copyOf(bytes, bytes.length * 2);
    }
    if (bit) {
      bytes[byteIndex] |= (byte) (1 << (size & 7));
    }
    size++;
  }

  boolean get(int index) {
    return (bytes[index >>> 3] & (1 << (index & 7))) != 0;
  }

  int size() {
    return size;
  }

  int byteCount() {
    return (size + 7) >>> 3;
  }

  /** The bitmap bytes, valid up to {@link #byteCount}, with zero padding past {@link #size}. */
  byte[] bytes() {
    return bytes;
  }

  /** Whether both hold the same bits. */
  boolean sameAs(Bits other) {
    return size == other.size && Arrays.equals(bytes, 0, byteCount(), other.bytes, 0, byteCount());
  }

  int contentHash() {
    int hash = size;
    for (int i = 0; i < byteCount(); i++) {
      hash = 31 * hash + bytes[i];
    }
    return hash;
  }
}
