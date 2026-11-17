package org.maplibre.mlt.encoder;

import java.util.Arrays;

/** A growable list of primitive ints, reused across layers. */
final class IntList {

  private int[] values = new int[64];
  private int size;

  int size() {
    return size;
  }

  int get(int index) {
    return values[index];
  }

  int last() {
    return values[size - 1];
  }

  void add(int value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, size * 2);
    }
    values[size++] = value;
  }

  /** Adds two values, for the x and y of a vertex, with one capacity check. */
  void add(int first, int second) {
    if (size + 2 > values.length) {
      values = Arrays.copyOf(values, values.length * 2);
    }
    values[size] = first;
    values[size + 1] = second;
    size += 2;
  }

  void truncate(int newSize) {
    size = newSize;
  }

  void clear() {
    size = 0;
  }

  /** The backing array, valid up to {@link #size}. */
  int[] array() {
    return values;
  }
}
