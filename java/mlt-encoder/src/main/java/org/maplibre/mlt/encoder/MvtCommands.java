package org.maplibre.mlt.encoder;

/** Reads MVT geometry command streams (MVT spec section 4.3) into {@link Geometries}. */
final class MvtCommands {

  private static final int MOVE_TO = 1;
  private static final int LINE_TO = 2;
  private static final int CLOSE_PATH = 7;

  private MvtCommands() {}

  /**
   * Adds one feature of {@code type} to {@code out}. Several points, lines or polygons make it the
   * matching MLT {@code Multi*} type.
   *
   * @throws IllegalArgumentException if the commands hold no vertex or are malformed, in which case
   *     nothing was added
   */
  static void add(MvtGeometryType type, int[] commands, Geometries out) {
    try {
      int baseType =
          switch (type) {
            case POINT -> readPoints(commands, out);
            case LINESTRING -> readLines(commands, out);
            case POLYGON -> readPolygons(commands, out);
          };
      out.endFeature(out.partsInCurrentFeature() > 1 ? baseType + Geometries.MULTIPOINT : baseType);
    } catch (RuntimeException e) {
      out.rollbackFeature();
      throw e instanceof IllegalArgumentException iae
          ? iae
          : new IllegalArgumentException("malformed MVT geometry commands", e);
    }
  }

  private static int readPoints(int[] commands, Geometries out) {
    var cursor = new Cursor(commands);
    while (cursor.nextCommand()) {
      if (cursor.command != MOVE_TO) {
        throw new IllegalArgumentException("only MoveTo is allowed in a point geometry");
      }
      for (int c = 0; c < cursor.count; c++) {
        cursor.addVertex(out);
        out.endRing();
        out.endPart();
      }
    }
    return Geometries.POINT;
  }

  private static int readLines(int[] commands, Geometries out) {
    var cursor = new Cursor(commands);
    while (cursor.nextCommand()) {
      if (cursor.command == MOVE_TO) {
        for (int c = 0; c < cursor.count; c++) {
          endLine(out);
          cursor.addVertex(out);
        }
      } else if (cursor.command == LINE_TO && out.verticesInCurrentRing() > 0) {
        for (int c = 0; c < cursor.count; c++) {
          cursor.addVertex(out);
        }
      } else {
        throw new IllegalArgumentException("a line must start with MoveTo, then LineTo");
      }
    }
    endLine(out);
    return Geometries.LINESTRING;
  }

  private static void endLine(Geometries out) {
    if (out.verticesInCurrentRing() > 0) {
      out.endRing();
      out.endPart();
    }
  }

  private static int readPolygons(int[] commands, Geometries out) {
    var cursor = new Cursor(commands);
    boolean polygonOpen = false;
    while (cursor.nextCommand()) {
      switch (cursor.command) {
        case MOVE_TO -> {
          if (cursor.count != 1 || out.verticesInCurrentRing() > 0) {
            throw new IllegalArgumentException("polygon ring without ClosePath");
          }
          cursor.addVertex(out);
        }
        case LINE_TO -> {
          if (out.verticesInCurrentRing() == 0) {
            throw new IllegalArgumentException("LineTo without a MoveTo before it");
          }
          for (int c = 0; c < cursor.count; c++) {
            cursor.addVertex(out);
          }
        }
        case CLOSE_PATH -> {
          int ringStart = out.vertexCount() - out.verticesInCurrentRing();
          if (ringStart == out.vertexCount()) {
            throw new IllegalArgumentException("ClosePath outside a polygon ring");
          }
          // MVT exteriors have a positive area and holes a negative one. MLT keeps that winding and
          // makes the first ring of each polygon its exterior, so every positive ring starts one.
          if (!polygonOpen || out.signedArea(ringStart, out.vertexCount()) > 0) {
            if (polygonOpen) {
              out.endPart();
            }
            polygonOpen = true;
          }
          out.endRing();
        }
        default -> throw new IllegalArgumentException("unknown MVT command " + cursor.command);
      }
    }
    if (out.verticesInCurrentRing() > 0) {
      throw new IllegalArgumentException("polygon ring without ClosePath");
    }
    if (polygonOpen) {
      out.endPart();
    }
    return Geometries.POLYGON;
  }

  /** Walks the commands, keeping the current position as MVT does: each parameter is a delta. */
  private static final class Cursor {
    private final int[] commands;
    private int index;
    private int x;
    private int y;
    int command;
    int count;

    Cursor(int[] commands) {
      this.commands = commands;
    }

    boolean nextCommand() {
      if (index == commands.length) {
        return false;
      }
      command = commands[index] & 0x7;
      count = commands[index++] >>> 3;
      return true;
    }

    void addVertex(Geometries out) {
      x += unzigzag(commands[index++]);
      y += unzigzag(commands[index++]);
      out.addVertex(x, y);
    }

    private static int unzigzag(int value) {
      return (value >>> 1) ^ -(value & 1);
    }
  }
}
