package org.maplibre.mlt.encoder;

/**
 * The values of a layer's geometry streams, which mean the same in MLT v1 and v2: only how each
 * version lays the streams out differs.
 *
 * <p>Each length stream holds a length only where an item can hold more than one entry: a multi
 * geometry's number of parts, a polygon's number of rings, and a ring's or line's number of
 * vertices. With polygons in the layer, part lengths hold the ring count of each polygon and ring
 * lengths the vertex count of each ring and line. Without them, part lengths hold the vertex count
 * of each line.
 */
final class GeometryStreams {

  final LongList types = new LongList();
  final LongList geometryLengths = new LongList();
  final LongList partLengths = new LongList();
  final LongList ringLengths = new LongList();

  /**
   * The vertex stream's VarInt payload: per vertex, the zigzag deltas of x and y from the previous
   * vertex of the layer. Both versions store it this way unless FastPFOR is smaller.
   */
  final ByteWriter vertexVarints = new ByteWriter();

  /** The same words as ints, only filled when FastPFOR may store them. */
  final IntList vertexWords = new IntList();

  boolean hasMulti;
  boolean hasLines;
  boolean hasPolygons;

  /** The type every feature has, or -1 if they differ. */
  int uniformType;

  void fill(Geometries geometries, boolean keepVertexWords) {
    types.clear();
    geometryLengths.clear();
    partLengths.clear();
    ringLengths.clear();
    vertexVarints.clear();
    vertexWords.clear();
    hasMulti = false;
    hasLines = false;
    hasPolygons = false;
    uniformType = geometries.featureCount() > 0 ? geometries.type(0) : -1;
    for (int f = 0; f < geometries.featureCount(); f++) {
      int type = geometries.type(f);
      types.add(type);
      hasMulti |= Geometries.isMulti(type);
      hasLines |= Geometries.baseType(type) == Geometries.LINESTRING;
      hasPolygons |= Geometries.baseType(type) == Geometries.POLYGON;
      if (type != uniformType) {
        uniformType = -1;
      }
    }
    LongList lineLengths = hasPolygons ? ringLengths : partLengths;
    for (int f = 0; f < geometries.featureCount(); f++) {
      int type = geometries.type(f);
      if (Geometries.isMulti(type)) {
        geometryLengths.add(geometries.partEnd(f) - geometries.partStart(f));
      }
      for (int part = geometries.partStart(f); part < geometries.partEnd(f); part++) {
        switch (Geometries.baseType(type)) {
          case Geometries.LINESTRING -> lineLengths.add(vertexCount(geometries, part));
          case Geometries.POLYGON -> {
            partLengths.add(geometries.ringEnd(part) - geometries.ringStart(part));
            for (int ring = geometries.ringStart(part); ring < geometries.ringEnd(part); ring++) {
              ringLengths.add(geometries.vertexEnd(ring) - geometries.vertexStart(ring));
            }
          }
          default -> {}
        }
      }
    }
    int[] xy = geometries.xyArray();
    int x = 0;
    int y = 0;
    for (int v = 0; v < geometries.vertexCount(); v++) {
      int dx = zigzag(xy[2 * v] - x);
      int dy = zigzag(xy[2 * v + 1] - y);
      vertexVarints.writeVarint(Integer.toUnsignedLong(dx));
      vertexVarints.writeVarint(Integer.toUnsignedLong(dy));
      if (keepVertexWords) {
        vertexWords.add(dx, dy);
      }
      x = xy[2 * v];
      y = xy[2 * v + 1];
    }
  }

  private static int vertexCount(Geometries geometries, int part) {
    int ring = geometries.ringStart(part);
    return geometries.vertexEnd(ring) - geometries.vertexStart(ring);
  }

  private static int zigzag(int value) {
    return (value << 1) ^ (value >> 31);
  }
}
