package org.maplibre.mlt.encoder;

import java.util.Arrays;

/** The feature ids of a layer, for the features that have one. */
final class IdColumn {

  private final IntList features = new IntList();
  private final LongList ids = new LongList();
  private boolean fitIn32Bits = true;

  void clear() {
    features.clear();
    ids.clear();
    fitIn32Bits = true;
  }

  /** Sets the id (read as unsigned) of {@code feature}, which must be later than every other. */
  void add(int feature, long id) {
    features.add(feature);
    ids.add(id);
    fitIn32Bits &= (id >>> 32) == 0;
  }

  int count() {
    return ids.size();
  }

  /** The ids in feature order. */
  LongList ids() {
    return ids;
  }

  /** Whether every id fits in an unsigned 32-bit {@code Id} column, or needs a {@code LongId}. */
  boolean fitIn32Bits() {
    return fitIn32Bits;
  }

  void presence(int featureCount, Bits out) {
    out.clear();
    int next = 0;
    for (int f = 0; f < featureCount; f++) {
      boolean present = next < features.size() && features.get(next) == f;
      if (present) {
        next++;
      }
      out.add(present);
    }
  }

  /** Returns a copy with the id of feature {@code f} moved to feature {@code newIndex[f]}. */
  IdColumn reordered(int[] newIndex) {
    long[] byNewIndex = new long[count()];
    for (int i = 0; i < count(); i++) {
      byNewIndex[i] = ((long) newIndex[features.get(i)] << 32) | i;
    }
    Arrays.sort(byNewIndex);
    var copy = new IdColumn();
    for (long entry : byNewIndex) {
      copy.add((int) (entry >>> 32), ids.get((int) entry));
    }
    return copy;
  }
}
