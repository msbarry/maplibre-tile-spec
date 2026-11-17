package org.maplibre.mlt.encoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything added to one layer: its geometries, ids and property columns, in feature order and
 * independent of the MLT version it is written to.
 */
final class Layer {

  String name;
  int extent;
  final Geometries geometries = new Geometries();
  IdColumn ids = new IdColumn();
  final List<PropertyColumn> columns = new ArrayList<>();
  private final Map<String, Integer> keys = new HashMap<>();

  void begin(String name, int extent) {
    this.name = name;
    this.extent = extent;
    geometries.clear();
    ids.clear();
    columns.clear();
    keys.clear();
  }

  int featureCount() {
    return geometries.featureCount();
  }

  int propertyKey(String name) {
    return keys.computeIfAbsent(
        name,
        key -> {
          columns.add(new PropertyColumn(key));
          return columns.size() - 1;
        });
  }

  /**
   * Returns this layer with its features in {@code order}, a copy unless that is the input order.
   */
  Layer reordered(FeatureOrder order) {
    if (order == FeatureOrder.INPUT || featureCount() < 2) {
      return this;
    }
    int[] featureAt = order == FeatureOrder.ID ? orderById() : orderByCurve(order);
    int[] newIndex = new int[featureAt.length];
    for (int i = 0; i < featureAt.length; i++) {
      newIndex[featureAt[i]] = i;
    }
    var copy = new Layer();
    copy.begin(name, extent);
    for (int f : featureAt) {
      copy.geometries.copyFeature(geometries, f);
    }
    copy.ids = ids.reordered(newIndex);
    for (PropertyColumn column : columns) {
      copy.columns.add(column.reordered(newIndex));
    }
    return copy;
  }

  private int[] orderById() {
    long[] idOf = new long[featureCount()];
    // Features without an id sort last, as the largest unsigned value.
    Arrays.fill(idOf, -1L);
    var bits = new Bits();
    ids.presence(featureCount(), bits);
    for (int f = 0, next = 0; f < featureCount(); f++) {
      if (bits.get(f)) {
        idOf[f] = ids.ids().get(next++);
      }
    }
    // A stable sort, so features with equal ids keep their input order.
    return Arrays.stream(sequence(featureCount()))
        .boxed()
        .sorted(Comparator.comparing(f -> idOf[f], Long::compareUnsigned))
        .mapToInt(Integer::intValue)
        .toArray();
  }

  private int[] orderByCurve(FeatureOrder order) {
    var curve = SpaceFillingCurve.covering(geometries);
    long[] keyAndFeature = new long[featureCount()];
    for (int f = 0; f < featureCount(); f++) {
      int firstVertex = geometries.vertexStart(geometries.ringStart(geometries.partStart(f)));
      int x = geometries.x(firstVertex);
      int y = geometries.y(firstVertex);
      long key = order == FeatureOrder.HILBERT ? curve.hilbert(x, y) : curve.morton(x, y);
      keyAndFeature[f] = (key << 32) | f;
    }
    // Sorting on the feature in the low bits too keeps features with equal keys in input order.
    Arrays.sort(keyAndFeature);
    int[] featureAt = new int[featureCount()];
    for (int i = 0; i < featureAt.length; i++) {
      featureAt[i] = (int) keyAndFeature[i];
    }
    return featureAt;
  }

  private static int[] sequence(int count) {
    int[] result = new int[count];
    Arrays.setAll(result, i -> i);
    return result;
  }
}
