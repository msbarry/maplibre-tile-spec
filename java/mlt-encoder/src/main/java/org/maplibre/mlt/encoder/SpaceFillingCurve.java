package org.maplibre.mlt.encoder;

/**
 * Hilbert and Z-order (Morton) indexes of vertices on a grid covering every vertex of a layer, as
 * sort keys that keep nearby vertices close in order.
 */
final class SpaceFillingCurve {

  /**
   * Grids larger than this are scaled down, which only makes the order coarser. It keeps every
   * index below 2^30, so callers can pack an index and a feature number into one long.
   */
  private static final int MAX_BITS = 15;

  private final int shift;
  private final int bits;
  private final int scaleDown;

  private SpaceFillingCurve(int shift, int bits, int scaleDown) {
    this.shift = shift;
    this.bits = bits;
    this.scaleDown = scaleDown;
  }

  /** Returns the smallest curve whose grid holds every vertex of {@code geometries}. */
  static SpaceFillingCurve covering(Geometries geometries) {
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    for (int v = 0; v < geometries.vertexCount(); v++) {
      min = Math.min(min, Math.min(geometries.x(v), geometries.y(v)));
      max = Math.max(max, Math.max(geometries.x(v), geometries.y(v)));
    }
    int shift = Math.max(0, -min);
    long extent = (long) max + shift;
    int bits = Math.max(1, 64 - Long.numberOfLeadingZeros(extent));
    return new SpaceFillingCurve(shift, Math.min(bits, MAX_BITS), Math.max(0, bits - MAX_BITS));
  }

  long hilbert(int x, int y) {
    int n = 1 << bits;
    int hx = gridCoordinate(x);
    int hy = gridCoordinate(y);
    long index = 0;
    for (int s = n >>> 1; s > 0; s >>>= 1) {
      int rx = (hx & s) != 0 ? 1 : 0;
      int ry = (hy & s) != 0 ? 1 : 0;
      index += (long) s * s * ((3 * rx) ^ ry);
      // Rotates the quadrant so the curve inside it starts and ends where the parent's does.
      if (ry == 0) {
        if (rx == 1) {
          hx = n - 1 - hx;
          hy = n - 1 - hy;
        }
        int swap = hx;
        hx = hy;
        hy = swap;
      }
    }
    return index;
  }

  long morton(int x, int y) {
    return spreadBits(gridCoordinate(x)) | (spreadBits(gridCoordinate(y)) << 1);
  }

  private int gridCoordinate(int value) {
    return (int) (((long) value + shift) >>> scaleDown);
  }

  /** Moves bit {@code i} of a 15-bit value to bit {@code 2i}. */
  private static long spreadBits(int value) {
    long v = value & 0xFFFFL;
    v = (v | (v << 8)) & 0x00FF00FFL;
    v = (v | (v << 4)) & 0x0F0F0F0FL;
    v = (v | (v << 2)) & 0x33333333L;
    v = (v | (v << 1)) & 0x55555555L;
    return v;
  }
}
