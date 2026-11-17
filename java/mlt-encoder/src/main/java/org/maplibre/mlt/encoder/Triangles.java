package org.maplibre.mlt.encoder;

import java.util.Arrays;
import java.util.List;
import org.maplibre.earcut4j.Earcut;

/**
 * The triangles of every polygon of a layer, cut with earcut, which adds no vertices, so each
 * triangle is three indices into the layer's vertices.
 */
final class Triangles {

  /**
   * For each {@code Polygon} or {@code MultiPolygon} feature, in order, its number of triangles.
   */
  final LongList countPerPolygonFeature = new LongList();

  /** For each {@code Polygon} or {@code MultiPolygon} feature, the index of its first vertex. */
  final IntList firstVertexPerPolygonFeature = new IntList();

  /** Three vertex indices per triangle, counted from the layer's first vertex. */
  final IntList indices = new IntList();

  int triangleCount() {
    return indices.size() / 3;
  }

  void fill(Geometries geometries) {
    countPerPolygonFeature.clear();
    firstVertexPerPolygonFeature.clear();
    indices.clear();
    for (int f = 0; f < geometries.featureCount(); f++) {
      if (Geometries.baseType(geometries.type(f)) == Geometries.POLYGON) {
        int trianglesBefore = triangleCount();
        for (int part = geometries.partStart(f); part < geometries.partEnd(f); part++) {
          addPolygon(geometries, part);
        }
        countPerPolygonFeature.add(triangleCount() - trianglesBefore);
        firstVertexPerPolygonFeature.add(
            geometries.vertexStart(geometries.ringStart(geometries.partStart(f))));
      }
    }
  }

  private void addPolygon(Geometries geometries, int part) {
    int vertexCount =
        geometries.vertexEnd(geometries.ringEnd(part) - 1)
            - geometries.vertexStart(geometries.ringStart(part));
    double[] coordinates = new double[2 * vertexCount];
    int[] layerVertex = new int[vertexCount];
    int[] holeStarts = new int[geometries.ringEnd(part) - geometries.ringStart(part) - 1];
    int holeCount = 0;
    int kept = 0;
    for (int ring = geometries.ringStart(part); ring < geometries.ringEnd(part); ring++) {
      int start = geometries.vertexStart(ring);
      int end = geometries.vertexEnd(ring);
      // A ring without area holds no triangle, and earcut can loop forever on one.
      if (geometries.signedArea(start, end) == 0) {
        if (ring == geometries.ringStart(part)) {
          return;
        }
        continue;
      }
      if (ring != geometries.ringStart(part)) {
        holeStarts[holeCount++] = kept;
      }
      for (int v = start; v < end; v++) {
        coordinates[2 * kept] = geometries.x(v);
        coordinates[2 * kept + 1] = geometries.y(v);
        layerVertex[kept++] = v;
      }
    }
    List<Integer> triangles =
        Earcut.earcut(
            Arrays.copyOf(coordinates, 2 * kept), Arrays.copyOf(holeStarts, holeCount), 2);
    for (int index : triangles) {
      indices.add(layerVertex[index]);
    }
  }
}
