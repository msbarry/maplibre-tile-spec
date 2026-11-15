package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * The geometry column of one layer, built straight from MVT command streams.
 *
 * <p>MVT command parameters are zigzag deltas from the previous vertex, which is exactly what the
 * MLT componentwise-delta vertex stream holds, except that MVT starts every feature at (0, 0) while
 * MLT continues from the previous feature's last vertex. So the vertex stream is copied from the
 * commands, and only each feature's first vertex is re-encoded.
 */
final class GeometryColumn {

  private static final int POINT = 0;
  private static final int LINESTRING = 1;
  private static final int POLYGON = 2;
  private static final int MULTI = 3;

  private static final int MOVE_TO = 1;
  private static final int LINE_TO = 2;
  private static final int CLOSE_PATH = 7;

  private static final int VERTEX_STREAM = 0x13;
  private static final int COMPONENTWISE_DELTA_VARINT = 0b010_000_10;
  private static final int COMPONENTWISE_DELTA_FASTPFOR = 0b010_000_01;

  private final LongList types = new LongList();
  private final LongList geometryCounts = new LongList();
  private final LongList polygonRingCounts = new LongList();
  private final LongList vertexCounts = new LongList();
  private final ByteWriter vertices = new ByteWriter();
  private int vertexCount;

  /** The zigzag deltas, when FastPFOR may store them. */
  private int[] vertexWords;

  private final Codecs codecs;
  private int lastX;
  private int lastY;
  private boolean hasLines;
  private boolean hasPolygons;
  private boolean hasMulti;

  GeometryColumn(Codecs codecs) {
    this.codecs = codecs;
    this.vertexWords = codecs.fastPfor != null ? new int[1024] : null;
  }

  void clear() {
    types.clear();
    geometryCounts.clear();
    polygonRingCounts.clear();
    vertexCounts.clear();
    vertices.clear();
    vertexCount = 0;
    lastX = 0;
    lastY = 0;
    hasLines = false;
    hasPolygons = false;
    hasMulti = false;
  }

  /**
   * Adds the geometry of one feature.
   *
   * @throws IllegalArgumentException if the commands hold no vertex or are malformed, in which case
   *     nothing was added
   */
  void add(MvtGeometryType type, int[] commands) {
    int baseType =
        switch (type) {
          case POINT -> POINT;
          case LINESTRING -> LINESTRING;
          case POLYGON -> POLYGON;
        };
    int geometryCountsBefore = geometryCounts.size();
    int polygonsBefore = polygonRingCounts.size();
    int vertexCountsBefore = vertexCounts.size();
    int vertexBytesBefore = vertices.size();
    int vertexCountBefore = vertexCount;
    try {
      int geometries = parse(baseType, commands);
      if (geometries == 0) {
        throw new IllegalArgumentException("geometry has no vertices");
      }
      types.add(geometries > 1 ? baseType + MULTI : baseType);
      if (geometries > 1) {
        geometryCounts.add(geometries);
        hasMulti = true;
      }
      hasLines |= baseType == LINESTRING;
      hasPolygons |= baseType == POLYGON;
    } catch (RuntimeException e) {
      geometryCounts.truncate(geometryCountsBefore);
      polygonRingCounts.truncate(polygonsBefore);
      vertexCounts.truncate(vertexCountsBefore);
      vertices.truncate(vertexBytesBefore);
      vertexCount = vertexCountBefore;
      throw e instanceof IllegalArgumentException iae
          ? iae
          : new IllegalArgumentException("malformed MVT geometry commands", e);
    }
  }

  /**
   * Appends the vertices and lengths of one feature, and returns how many points, lines or polygons
   * it has.
   */
  private int parse(int baseType, int[] commands) {
    int geometries = 0;
    int ringVertices = 0;
    int x = 0;
    int y = 0;
    long ringArea = 0;
    int ringStartX = 0;
    int ringStartY = 0;
    boolean first = true;
    int i = 0;
    while (i < commands.length) {
      int command = commands[i] & 0x7;
      int count = commands[i++] >>> 3;
      if (command == CLOSE_PATH) {
        if (baseType != POLYGON || ringVertices == 0) {
          throw new IllegalArgumentException("ClosePath outside a polygon ring");
        }
        ringArea += (long) x * ringStartY - (long) ringStartX * y;
        // MVT exteriors have a positive area and holes a negative one. MLT keeps that winding and
        // makes the first ring of each polygon its exterior, so every positive ring starts one.
        if (geometries == 0 || ringArea > 0) {
          polygonRingCounts.add(1);
          geometries++;
        } else {
          increment(polygonRingCounts);
        }
        vertexCounts.add(ringVertices);
        ringVertices = 0;
        continue;
      }
      if (command != MOVE_TO && command != LINE_TO) {
        throw new IllegalArgumentException("unknown MVT command " + command);
      }
      if (command == LINE_TO && (baseType == POINT || ringVertices == 0)) {
        throw new IllegalArgumentException("LineTo without a MoveTo before it");
      }
      if (command == MOVE_TO && baseType == POLYGON && ringVertices > 0) {
        throw new IllegalArgumentException("polygon ring without ClosePath");
      }
      for (int c = 0; c < count; c++) {
        int dx = commands[i++];
        int dy = commands[i++];
        int previousX = x;
        int previousY = y;
        x += unzigzag(dx);
        y += unzigzag(dy);
        if (first) {
          addVertex(zigzag(x - lastX), zigzag(y - lastY));
          first = false;
        } else {
          addVertex(dx, dy);
        }
        if (command == MOVE_TO) {
          switch (baseType) {
            case POINT -> geometries++;
            case LINESTRING -> {
              if (ringVertices > 0) {
                vertexCounts.add(ringVertices);
              }
              geometries++;
              ringVertices = 0;
            }
            default -> {
              ringStartX = x;
              ringStartY = y;
              ringArea = 0;
            }
          }
        } else if (baseType == POLYGON) {
          ringArea += (long) previousX * y - (long) x * previousY;
        }
        ringVertices++;
      }
    }
    if (baseType == LINESTRING && ringVertices > 0) {
      vertexCounts.add(ringVertices);
    } else if (baseType == POLYGON && ringVertices > 0) {
      throw new IllegalArgumentException("polygon ring without ClosePath");
    }
    if (!first) {
      lastX = x;
      lastY = y;
    }
    return geometries;
  }

  /**
   * Writes the column's data: the types stream, the length streams its geometry types need, and the
   * vertex stream.
   */
  void writeTo(ByteWriter out) {
    var typesStream = IntegerStream.of(types, false, false, codecs);
    int streamCount = 2 + (hasMulti ? 1 : 0) + (hasPolygons ? 2 : hasLines ? 1 : 0);
    out.writeVarint(streamCount);
    // The Rust and Java reference encoders both tag the types stream Length/VarBinary.
    typesStream.writeTo(out, 0x30);
    if (hasMulti) {
      IntegerStream.of(geometryCounts, false, false, codecs).writeTo(out, 0x31);
    }
    // With polygons present, Parts holds the ring count of each polygon and Rings the vertex count
    // of each ring and line. Without them, Parts holds the vertex count of each line.
    if (hasPolygons) {
      IntegerStream.of(polygonRingCounts, false, false, codecs).writeTo(out, 0x32);
      IntegerStream.of(vertexCounts, false, false, codecs).writeTo(out, 0x33);
    } else if (hasLines) {
      IntegerStream.of(vertexCounts, false, false, codecs).writeTo(out, 0x32);
    }
    writeVertices(out);
  }

  private void writeVertices(ByteWriter out) {
    int words = 2 * vertexCount;
    out.writeByte(VERTEX_STREAM);
    if (vertexWords != null && words >= FastPfor.MIN_VALUES) {
      int[] encoded = codecs.fastPfor.encode(vertexWords, words);
      if (4 * encoded.length < vertices.size()) {
        out.writeByte(COMPONENTWISE_DELTA_FASTPFOR);
        out.writeVarint(words);
        out.writeVarint(4 * encoded.length);
        FastPfor.write(out, encoded);
        return;
      }
    }
    out.writeByte(COMPONENTWISE_DELTA_VARINT);
    out.writeVarint(words);
    out.writeVarint(vertices.size());
    out.writeBytes(vertices);
  }

  /** Appends one vertex, as the zigzag deltas of its x and y from the previous vertex. */
  private void addVertex(int zigzagDx, int zigzagDy) {
    vertices.writeVarint(Integer.toUnsignedLong(zigzagDx));
    vertices.writeVarint(Integer.toUnsignedLong(zigzagDy));
    if (vertexWords != null) {
      if (2 * vertexCount + 2 > vertexWords.length) {
        vertexWords = Arrays.copyOf(vertexWords, vertexWords.length * 2);
      }
      vertexWords[2 * vertexCount] = zigzagDx;
      vertexWords[2 * vertexCount + 1] = zigzagDy;
    }
    vertexCount++;
  }

  private static void increment(LongList list) {
    list.set(list.size() - 1, list.get(list.size() - 1) + 1);
  }

  private static int zigzag(int value) {
    return (value << 1) ^ (value >> 31);
  }

  private static int unzigzag(int value) {
    return (value >>> 1) ^ -(value & 1);
  }
}
