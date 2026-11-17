package org.maplibre.mlt.encoder;

/**
 * The geometry of every feature in a layer, independent of the MLT version it is written to.
 *
 * <p>Each feature has one or more parts (a point, a line or a polygon), each part one or more rings
 * (a point or a line is a single ring), and each ring one or more vertices. They are stored as
 * offset arrays, as in GeoArrow: the parts of feature {@code f} are {@code partOffsets[f]} up to
 * {@code partOffsets[f + 1]}, and so on down to the vertices. Vertices are absolute tile
 * coordinates, so features can be reordered and polygons tessellated before they are written.
 *
 * <p>Polygon rings follow MVT: no closing vertex, exteriors have a positive {@link #signedArea} and
 * holes a negative one.
 */
final class Geometries {

  static final int POINT = 0;
  static final int LINESTRING = 1;
  static final int POLYGON = 2;
  static final int MULTIPOINT = 3;
  static final int MULTILINESTRING = 4;
  static final int MULTIPOLYGON = 5;

  private final IntList types = new IntList();
  private final IntList partOffsets = new IntList();
  private final IntList ringOffsets = new IntList();
  private final IntList vertexOffsets = new IntList();
  private final IntList xy = new IntList();

  Geometries() {
    clear();
  }

  void clear() {
    types.clear();
    partOffsets.clear();
    ringOffsets.clear();
    vertexOffsets.clear();
    xy.clear();
    partOffsets.add(0);
    ringOffsets.add(0);
    vertexOffsets.add(0);
  }

  int featureCount() {
    return types.size();
  }

  /** The MLT geometry type of feature {@code f}: {@link #POINT} to {@link #MULTIPOLYGON}. */
  int type(int f) {
    return types.get(f);
  }

  int partStart(int feature) {
    return partOffsets.get(feature);
  }

  int partEnd(int feature) {
    return partOffsets.get(feature + 1);
  }

  int ringStart(int part) {
    return ringOffsets.get(part);
  }

  int ringEnd(int part) {
    return ringOffsets.get(part + 1);
  }

  int vertexStart(int ring) {
    return vertexOffsets.get(ring);
  }

  int vertexEnd(int ring) {
    return vertexOffsets.get(ring + 1);
  }

  int vertexCount() {
    return xy.size() / 2;
  }

  int x(int vertex) {
    return xy.get(2 * vertex);
  }

  int y(int vertex) {
    return xy.get(2 * vertex + 1);
  }

  /** The x and y of every vertex, interleaved, valid up to {@code 2 * vertexCount()}. */
  int[] xyArray() {
    return xy.array();
  }

  /**
   * Returns the base type of an MLT geometry type: {@link #POINT}, {@link #LINESTRING} or {@link
   * #POLYGON}.
   */
  static int baseType(int type) {
    return type % 3;
  }

  static boolean isMulti(int type) {
    return type >= MULTIPOINT;
  }

  // Building a feature: vertices, then endRing, then endPart, then endFeature. A feature that turns
  // out to be invalid is dropped with rollbackFeature, which leaves the layer as it was.

  void addVertex(int x, int y) {
    xy.add(x, y);
  }

  /** Ends the ring made of the vertices added since the last ring. Returns its vertex count. */
  int endRing() {
    int vertices = vertexCount() - vertexOffsets.last();
    vertexOffsets.add(vertexCount());
    return vertices;
  }

  /** Ends the part made of the rings ended since the last part. */
  void endPart() {
    ringOffsets.add(vertexOffsets.size() - 1);
  }

  /**
   * Ends the feature made of the parts ended since the last feature.
   *
   * @throws IllegalArgumentException if it has no part, in which case it is rolled back
   */
  void endFeature(int type) {
    int parts = ringOffsets.size() - 1 - partOffsets.last();
    if (parts == 0) {
      rollbackFeature();
      throw new IllegalArgumentException("geometry has no vertices");
    }
    partOffsets.add(ringOffsets.size() - 1);
    types.add(type);
  }

  /** Removes everything added since the last complete feature. */
  void rollbackFeature() {
    ringOffsets.truncate(partOffsets.last() + 1);
    vertexOffsets.truncate(ringOffsets.last() + 1);
    xy.truncate(2 * vertexOffsets.last());
  }

  /** The number of parts of the feature being built, so far. */
  int partsInCurrentFeature() {
    return ringOffsets.size() - 1 - partOffsets.last();
  }

  /** The vertices added since the current ring began. */
  int verticesInCurrentRing() {
    return vertexCount() - vertexOffsets.last();
  }

  /**
   * Twice the signed area of the ring from vertex {@code start} to {@code end} (exclusive), with
   * the sign MVT uses: positive for an exterior ring, negative for a hole.
   */
  long signedArea(int start, int end) {
    int[] values = xy.array();
    long area = 0;
    for (int i = start; i < end; i++) {
      int next = i + 1 < end ? i + 1 : start;
      area +=
          (long) values[2 * i] * values[2 * next + 1] - (long) values[2 * next] * values[2 * i + 1];
    }
    return area;
  }

  /** Reverses the order of vertices {@code start} to {@code end} (exclusive). */
  void reverseVertices(int start, int end) {
    int[] values = xy.array();
    for (int a = 2 * start, b = 2 * (end - 1); a < b; a += 2, b -= 2) {
      int x = values[a];
      int y = values[a + 1];
      values[a] = values[b];
      values[a + 1] = values[b + 1];
      values[b] = x;
      values[b + 1] = y;
    }
  }

  /** Appends feature {@code f} of {@code other}, so that a layer can be copied in another order. */
  void copyFeature(Geometries other, int f) {
    for (int part = other.partStart(f); part < other.partEnd(f); part++) {
      for (int ring = other.ringStart(part); ring < other.ringEnd(part); ring++) {
        for (int v = other.vertexStart(ring); v < other.vertexEnd(ring); v++) {
          addVertex(other.x(v), other.y(v));
        }
        endRing();
      }
      endPart();
    }
    endFeature(other.type(f));
  }
}
