package org.maplibre.mlt.encoder;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Reads JTS geometries in tile coordinates into {@link Geometries}. */
final class JtsGeometries {

  private JtsGeometries() {}

  /**
   * Splits {@code geometry} into the features MLT can store: one for a point, line or polygon
   * geometry or a collection of one kind, and one per kind for a collection that mixes them. Nested
   * collections are flattened and empty parts dropped, so an empty collection has none.
   */
  static List<Geometry> features(Geometry geometry) {
    if (!(geometry instanceof GeometryCollection)
        || geometry instanceof MultiPoint
        || geometry instanceof MultiLineString
        || geometry instanceof MultiPolygon) {
      return List.of(geometry);
    }
    List<Geometry> points = new ArrayList<>();
    List<Geometry> lines = new ArrayList<>();
    List<Geometry> polygons = new ArrayList<>();
    flatten(geometry, points, lines, polygons);
    var factory = geometry.getFactory();
    List<Geometry> features = new ArrayList<>(3);
    if (!points.isEmpty()) {
      features.add(factory.createMultiPoint(points.toArray(Point[]::new)));
    }
    if (!lines.isEmpty()) {
      features.add(factory.createMultiLineString(lines.toArray(LineString[]::new)));
    }
    if (!polygons.isEmpty()) {
      features.add(factory.createMultiPolygon(polygons.toArray(Polygon[]::new)));
    }
    return features;
  }

  private static void flatten(
      Geometry geometry, List<Geometry> points, List<Geometry> lines, List<Geometry> polygons) {
    for (int i = 0; i < geometry.getNumGeometries(); i++) {
      Geometry child = geometry.getGeometryN(i);
      switch (child) {
        case GeometryCollection collection when child != geometry ->
            flatten(collection, points, lines, polygons);
        case Point point when !point.isEmpty() -> points.add(point);
        case LineString line when !line.isEmpty() -> lines.add(line);
        case Polygon polygon when !polygon.isEmpty() -> polygons.add(polygon);
        default -> {}
      }
    }
  }

  /**
   * Adds one feature: a point, line or polygon geometry or a multi-geometry of one of them, as
   * {@link #features} splits collections. Coordinates are rounded to the nearest integer, and
   * polygon rings get the closing vertex dropped and their winding set to what MLT requires.
   *
   * @throws IllegalArgumentException if the geometry is empty or a mixed collection, in which case
   *     nothing was added
   */
  static void add(Geometry geometry, Geometries out) {
    int type =
        switch (geometry) {
          case Point ignored -> Geometries.POINT;
          case LineString ignored -> Geometries.LINESTRING;
          case Polygon ignored -> Geometries.POLYGON;
          case MultiPoint ignored -> Geometries.MULTIPOINT;
          case MultiLineString ignored -> Geometries.MULTILINESTRING;
          case MultiPolygon ignored -> Geometries.MULTIPOLYGON;
          default ->
              throw new IllegalArgumentException(
                  "split " + geometry.getGeometryType() + " with JtsGeometries.features first");
        };
    for (int i = 0; i < geometry.getNumGeometries(); i++) {
      switch (geometry.getGeometryN(i)) {
        case Point point when !point.isEmpty() -> addPart(point.getCoordinateSequence(), out);
        case LineString line when !line.isEmpty() -> addPart(line.getCoordinateSequence(), out);
        case Polygon polygon when !polygon.isEmpty() -> addPolygon(polygon, out);
        default -> {}
      }
    }
    out.endFeature(type);
  }

  private static void addPart(CoordinateSequence coordinates, Geometries out) {
    addVertices(coordinates, coordinates.size(), out);
    out.endRing();
    out.endPart();
  }

  private static void addPolygon(Polygon polygon, Geometries out) {
    addRing(polygon.getExteriorRing().getCoordinateSequence(), true, out);
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      LineString hole = polygon.getInteriorRingN(i);
      if (!hole.isEmpty()) {
        addRing(hole.getCoordinateSequence(), false, out);
      }
    }
    out.endPart();
  }

  /** Adds a closed JTS ring without its closing vertex, reversed if it winds the wrong way. */
  private static void addRing(CoordinateSequence ring, boolean exterior, Geometries out) {
    int start = out.vertexCount();
    addVertices(ring, ring.size() - 1, out);
    long area = out.signedArea(start, out.vertexCount());
    if (exterior ? area < 0 : area > 0) {
      out.reverseVertices(start, out.vertexCount());
    }
    out.endRing();
  }

  private static void addVertices(CoordinateSequence coordinates, int count, Geometries out) {
    for (int i = 0; i < count; i++) {
      out.addVertex(
          (int) Math.round(coordinates.getOrdinate(i, CoordinateSequence.X)),
          (int) Math.round(coordinates.getOrdinate(i, CoordinateSequence.Y)));
    }
  }
}
