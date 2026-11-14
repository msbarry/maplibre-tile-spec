package org.maplibre.mlt.encoder;

import java.util.Arrays;

/** A growable list of primitive longs, reused across layers. */
final class LongList {

  private long[] values = new long[64];
  private int size;

  int size() {
    return size;
  }

  long get(int index) {
    return values[index];
  }

  void add(long value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, size * 2);
    }
    values[size++] = value;
  }

  void set(int index, long value) {
    values[index] = value;
  }

  void truncate(int newSize) {
    size = newSize;
  }

  void clear() {
    size = 0;
  }
}
