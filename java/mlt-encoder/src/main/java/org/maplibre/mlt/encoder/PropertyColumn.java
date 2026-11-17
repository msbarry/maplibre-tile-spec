package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The values of one property key in a layer, independent of the MLT version it is written to. The
 * column's {@link ValueType} is picked from the values once the layer is complete.
 */
final class PropertyColumn {

  /** The type a column is stored as. */
  enum ValueType {
    BOOL,
    INT32,
    INT64,
    FLOAT,
    DOUBLE,
    STRING
  }

  private static final int BOOL = 1;
  private static final int INT = 2;
  private static final int FLOAT = 4;
  private static final int DOUBLE = 8;
  private static final int STRING = 16;

  final String name;
  final byte[] nameUtf8;
  private int[] features = new int[16];
  private Object[] values = new Object[16];
  private int count;
  private int kinds;
  private long minInt = Long.MAX_VALUE;
  private long maxInt = Long.MIN_VALUE;

  PropertyColumn(String name) {
    this.name = name;
    this.nameUtf8 = name.getBytes(StandardCharsets.UTF_8);
  }

  /** Sets the value of {@code feature}, which must be the latest feature that got one. */
  void set(int feature, Object value) {
    if (value == null) {
      return;
    }
    if (count > 0 && features[count - 1] == feature) {
      count--;
    } else if (count == features.length) {
      features = Arrays.copyOf(features, count * 2);
      values = Arrays.copyOf(values, count * 2);
    }
    int kind = kindOf(value);
    if (kind == INT) {
      long number = ((Number) value).longValue();
      minInt = Math.min(minInt, number);
      maxInt = Math.max(maxInt, number);
    }
    kinds |= kind;
    features[count] = feature;
    values[count++] = value;
  }

  /**
   * Booleans, integers (32 or 64-bit), floats or doubles when every value has that type, doubles
   * for a mix of numbers, and strings for anything else.
   */
  ValueType valueType() {
    if (kinds == BOOL) {
      return ValueType.BOOL;
    } else if (kinds == INT) {
      return minInt >= Integer.MIN_VALUE && maxInt <= Integer.MAX_VALUE
          ? ValueType.INT32
          : ValueType.INT64;
    } else if (kinds == FLOAT) {
      return ValueType.FLOAT;
    } else if ((kinds & ~(INT | FLOAT | DOUBLE)) == 0) {
      return ValueType.DOUBLE;
    }
    return ValueType.STRING;
  }

  /** The number of features with a value. */
  int count() {
    return count;
  }

  Object value(int index) {
    return values[index];
  }

  String stringValue(int index) {
    return values[index] instanceof String string ? string : String.valueOf(values[index]);
  }

  /** Sets bit {@code f} of {@code out} for each of the layer's features that has a value. */
  void presence(int featureCount, Bits out) {
    out.clear();
    int next = 0;
    for (int f = 0; f < featureCount; f++) {
      boolean present = next < count && features[next] == f;
      if (present) {
        next++;
      }
      out.add(present);
    }
  }

  /** Returns a copy with the values of feature {@code f} moved to feature {@code newIndex[f]}. */
  PropertyColumn reordered(int[] newIndex) {
    var copy = new PropertyColumn(name);
    copy.kinds = kinds;
    copy.minInt = minInt;
    copy.maxInt = maxInt;
    copy.count = count;
    copy.features = new int[count];
    copy.values = new Object[count];
    long[] byNewIndex = new long[count];
    for (int i = 0; i < count; i++) {
      byNewIndex[i] = ((long) newIndex[features[i]] << 32) | i;
    }
    Arrays.sort(byNewIndex);
    for (int i = 0; i < count; i++) {
      copy.features[i] = (int) (byNewIndex[i] >>> 32);
      copy.values[i] = values[(int) byNewIndex[i]];
    }
    return copy;
  }

  private static int kindOf(Object value) {
    return switch (value) {
      case Boolean ignored -> BOOL;
      case Integer ignored -> INT;
      case Long ignored -> INT;
      case Short ignored -> INT;
      case Byte ignored -> INT;
      case Float ignored -> FLOAT;
      case Double ignored -> DOUBLE;
      default -> STRING;
    };
  }
}
