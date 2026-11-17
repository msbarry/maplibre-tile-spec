package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes layers as MLT v2 (docs/specification/v2.md): a header byte, a layout byte, the shared
 * presence fields, the geometry section, then each column with its metadata next to its data.
 * Stream roles come from their position, so each stream header is one encoding byte, plus a value
 * count and byte length only where a decoder cannot derive them.
 */
final class V2LayerWriter implements LayerWriter {

  private static final int LAYER_TAG = 2;

  // Data types: the low nibble of a column type byte.
  private static final int TYPE_ID = 0x0;
  private static final int TYPE_LONG_ID = 0x1;
  private static final int TYPE_BOOL = 0x2;
  private static final int TYPE_INT32 = 0x5;
  private static final int TYPE_INT64 = 0x7;
  private static final int TYPE_FLOAT = 0x9;
  private static final int TYPE_DOUBLE = 0xA;
  private static final int TYPE_STRING = 0xB;
  private static final int TYPE_SHARED_DICTIONARY = 0xF;

  // The extension bits of a string column's leading stream: its layout.
  private static final int STRINGS_PLAIN = 0b00;
  private static final int STRINGS_DICTIONARY = 0b01;
  private static final int STRINGS_FSST_DICTIONARY = 0b11;

  // Geometry layouts: the low nibble of the layout byte.
  private static final int LAYOUT_POINTS = 0x0;
  private static final int LAYOUT_LINES = 0x4;
  private static final int LAYOUT_POLYGONS = 0x8;
  private static final int LAYOUT_MULTI = 0x2;
  private static final int LAYOUT_TRIANGLES = 0xC;
  private static final int LAYOUT_TRIANGLES_WITH_OUTLINES = 0xD;

  // Encoding bytes of the streams that are not integers.
  private static final int RAW_FLOATS = 0x00;
  private static final int BYTES_BLOB = 0x04;
  private static final int COMPONENTWISE_DELTA_VARINT = 0b0_010_10_00;
  private static final int COMPONENTWISE_DELTA_FASTPFOR = 0b0_010_11_00;

  /** Presence nibbles 4 to 10 name the layer's shared presence fields. */
  private static final int MAX_SHARED_PRESENCE = 7;

  /** A count that never matches, so the stream always stores its value count. */
  private static final int NO_IMPLIED_COUNT = -1;

  private final MltEncoder.Options options;
  private final Codecs codecs;
  private final ByteWriter body = new ByteWriter();
  private final ByteWriter geometrySection = new ByteWriter();
  private final ByteWriter columns = new ByteWriter();
  private final GeometryStreams geometry = new GeometryStreams();
  private final Triangles triangles = new Triangles();
  private final TriangleTrees trees = new TriangleTrees();
  private final Bits bits = new Bits();

  V2LayerWriter(MltEncoder.Options options, Codecs codecs) {
    this.options = options;
    this.codecs = codecs;
  }

  @Override
  public MltEncoder.LayerSize write(Layer layer, ByteWriter tile) {
    List<ColumnGroup> groups = ColumnGroup.of(layer.columns, options.sharedDictionaries());
    var presence = new Presence(layer, groups);
    int layout = writeGeometry(layer.geometries);

    columns.clear();
    int columnCount = groups.size();
    if (layer.ids.count() > 0) {
      columnCount++;
      writeIds(layer.ids, presence);
    }
    int beforeProperties = columns.size();
    for (ColumnGroup group : groups) {
      if (group.isSharedDictionary()) {
        writeSharedDictionary(group, presence);
      } else {
        writeColumn(group.members().getFirst(), presence);
      }
    }
    int propertyBytes = columns.size() - beforeProperties;

    body.clear();
    body.writeString(layer.name.getBytes(StandardCharsets.UTF_8));
    int uniformType = geometry.uniformType < 0 ? 0 : geometry.uniformType + 1;
    body.writeByte((uniformType << 4) | extentCode(layer.extent));
    body.writeVarint(layer.featureCount());
    presence.writeSharedFields(body, layout);
    body.writeBytes(geometrySection);
    body.writeVarint(columnCount);
    body.writeBytes(columns);

    int start = tile.size();
    tile.writeVarint(1 + body.size());
    tile.writeByte(LAYER_TAG);
    tile.writeBytes(body);
    return new MltEncoder.LayerSize(layer.name, tile.size() - start, propertyBytes);
  }

  /** v2 only stores power-of-two extents from 64 to 2^21, as {@code log2(extent) - 6}. */
  static int extentCode(int extent) {
    if (Integer.bitCount(extent) != 1 || extent < 64 || extent > (1 << 21)) {
      throw new IllegalArgumentException(
          "MLT v2 needs a power-of-two extent from 64 to 2097152, not " + extent);
    }
    return Integer.numberOfTrailingZeros(extent) - 6;
  }

  // Geometry

  /** Writes the geometry section to {@link #geometrySection} and returns its layout. */
  private int writeGeometry(Geometries geometries) {
    geometry.fill(geometries, codecs.fastPforEnabled());
    int featureCount = geometries.featureCount();
    boolean tessellated = false;
    if (options.tessellation() != Tessellation.NONE && geometry.hasPolygons) {
      triangles.fill(geometries);
      tessellated = triangles.triangleCount() > 0;
    }
    int layout;
    if (!tessellated) {
      layout =
          (geometry.hasPolygons
                  ? LAYOUT_POLYGONS
                  : geometry.hasLines ? LAYOUT_LINES : LAYOUT_POINTS)
              + (geometry.hasMulti ? LAYOUT_MULTI : 0);
    } else if (options.tessellation() == Tessellation.TRIANGLES_ONLY
        && !geometry.hasLines
        && triangles.countPerPolygonFeature.size() == featureCount) {
      layout = LAYOUT_TRIANGLES;
    } else {
      layout = LAYOUT_TRIANGLES_WITH_OUTLINES;
    }
    boolean outlines = layout != LAYOUT_TRIANGLES;
    boolean allOutlineStreams = layout == LAYOUT_TRIANGLES_WITH_OUTLINES;

    geometrySection.clear();
    if (geometry.uniformType < 0) {
      writeInts(geometrySection, geometry.types, featureCount);
    }
    if (outlines && (geometry.hasMulti || allOutlineStreams)) {
      writeInts(geometrySection, geometry.geometryLengths, featureCount);
    }
    if (outlines && (geometry.hasLines || geometry.hasPolygons || allOutlineStreams)) {
      writeInts(geometrySection, geometry.partLengths, featureCount);
    }
    if (outlines && (geometry.hasPolygons || allOutlineStreams)) {
      writeInts(geometrySection, geometry.ringLengths, featureCount);
    }
    if (tessellated) {
      trees.fill(triangles);
      writeInts(geometrySection, triangles.countPerPolygonFeature, featureCount);
      writeInts(geometrySection, trees.symbols, featureCount);
      writeInts(geometrySection, trees.cycles, featureCount);
    }
    writeVertices(geometries.vertexCount(), featureCount);
    return layout;
  }

  private void writeVertices(int vertexCount, int featureCount) {
    int wordCount = 2 * vertexCount;
    long varintBytes = geometry.vertexVarints.size();
    IntList fastPfor = null;
    if (codecs.fastPforEnabled() && wordCount >= V2FastPfor.MIN_VALUES) {
      fastPfor = codecs.v2FastPfor().encode(geometry.vertexWords.array(), wordCount);
      if (4L * fastPfor.size() >= varintBytes) {
        fastPfor = null;
      }
    }
    boolean explicitCount = vertexCount != featureCount;
    int encoding = fastPfor != null ? COMPONENTWISE_DELTA_FASTPFOR : COMPONENTWISE_DELTA_VARINT;
    geometrySection.writeByte((explicitCount ? 0x80 : 0) | encoding);
    if (explicitCount) {
      geometrySection.writeVarint(vertexCount);
    }
    if (fastPfor != null) {
      geometrySection.writeVarint(4L * fastPfor.size());
      for (int i = 0; i < fastPfor.size(); i++) {
        geometrySection.writeIntLE(fastPfor.get(i));
      }
    } else {
      geometrySection.writeVarint(varintBytes);
      geometrySection.writeBytes(geometry.vertexVarints);
    }
  }

  // Columns

  private void writeIds(IdColumn ids, Presence presence) {
    columns.writeByte(presence.nibble() << 4 | (ids.fitIn32Bits() ? TYPE_ID : TYPE_LONG_ID));
    presence.writeInline(columns);
    V2IntegerStream.of(ids.ids(), false, !ids.fitIn32Bits(), codecs)
        .writeTo(columns, ids.count(), 0);
  }

  private void writeColumn(PropertyColumn column, Presence presence) {
    PropertyColumn.ValueType valueType = column.valueType();
    int type =
        switch (valueType) {
          case BOOL -> TYPE_BOOL;
          case INT32 -> TYPE_INT32;
          case INT64 -> TYPE_INT64;
          case FLOAT -> TYPE_FLOAT;
          case DOUBLE -> TYPE_DOUBLE;
          case STRING -> TYPE_STRING;
        };
    columns.writeByte(presence.nibble() << 4 | type);
    columns.writeString(column.nameUtf8);
    presence.writeInline(columns);
    int count = column.count();
    switch (valueType) {
      case BOOL -> {
        bits.clear();
        for (int i = 0; i < count; i++) {
          bits.add((Boolean) column.value(i));
        }
        int encoding = V2Bits.bestEncoding(bits);
        columns.writeByte(encoding << 4);
        V2Bits.writePayload(columns, bits, encoding);
      }
      case INT32, INT64 -> {
        var ints = new LongList();
        for (int i = 0; i < count; i++) {
          ints.add(((Number) column.value(i)).longValue());
        }
        V2IntegerStream.of(ints, true, valueType == PropertyColumn.ValueType.INT64, codecs)
            .writeTo(columns, count, 0);
      }
      case FLOAT -> {
        columns.writeByte(RAW_FLOATS);
        for (int i = 0; i < count; i++) {
          columns.writeIntLE(Float.floatToRawIntBits((Float) column.value(i)));
        }
      }
      case DOUBLE -> {
        columns.writeByte(RAW_FLOATS);
        for (int i = 0; i < count; i++) {
          columns.writeLongLE(Double.doubleToRawLongBits(((Number) column.value(i)).doubleValue()));
        }
      }
      case STRING -> writeStrings(column);
    }
  }

  /**
   * Writes the plain layout (each value's bytes) or a dictionary layout (a code per value, then
   * each distinct value once, in one of the ways {@link V2Dictionary} offers), whichever is
   * smallest.
   */
  private void writeStrings(PropertyColumn column) {
    int count = column.count();
    var codes = new LongList();
    var dictionary = StringDictionary.of(column, codes);
    var plainLengths = new LongList();
    long plainBytes = 0;
    for (int i = 0; i < count; i++) {
      long length = dictionary.entryLengths.get((int) codes.get(i));
      plainLengths.add(length);
      plainBytes += length;
    }
    var plainLengthsStream = V2IntegerStream.of(plainLengths, false, false, codecs);
    long smallest = plainLengthsStream.size(count) + V2Dictionary.blobSize(plainBytes);
    V2Dictionary smallestDictionary = null;
    V2IntegerStream smallestCodes = null;
    // The streams after a string column's leading one are implied to have its count.
    for (V2Dictionary candidate : V2Dictionary.candidates(dictionary, codecs)) {
      var codesStream = V2IntegerStream.of(candidate.recode(codes), false, false, codecs);
      long size = codesStream.size(count) + candidate.size(count);
      if (size < smallest) {
        smallest = size;
        smallestDictionary = candidate;
        smallestCodes = codesStream;
      }
    }
    if (smallestDictionary == null) {
      plainLengthsStream.writeTo(columns, count, STRINGS_PLAIN);
      columns.writeByte(BYTES_BLOB);
      columns.writeVarint(plainBytes);
      for (int i = 0; i < count; i++) {
        columns.writeBytes(dictionary.entries.get((int) codes.get(i)));
      }
    } else {
      int layout = smallestDictionary.isFsst() ? STRINGS_FSST_DICTIONARY : STRINGS_DICTIONARY;
      smallestCodes.writeTo(columns, count, layout);
      smallestDictionary.writeTo(columns, count);
    }
  }

  /**
   * Writes a shared dictionary column: the dictionary, in whichever of the ways {@link
   * V2Dictionary} offers makes the column smallest, then each member as a code per value.
   */
  private void writeSharedDictionary(ColumnGroup group, Presence presence) {
    var dictionary = new StringDictionary();
    List<PropertyColumn> members = group.members();
    List<LongList> codes = new ArrayList<>();
    for (PropertyColumn member : members) {
      var memberCodes = new LongList();
      dictionary.addAll(member, memberCodes);
      codes.add(memberCodes);
    }
    long smallest = Long.MAX_VALUE;
    V2Dictionary smallestDictionary = null;
    List<V2IntegerStream> smallestCodes = null;
    for (V2Dictionary candidate : V2Dictionary.candidates(dictionary, codecs)) {
      // The streams of a shared dictionary have no implied count.
      long size = candidate.size(NO_IMPLIED_COUNT);
      List<V2IntegerStream> codesStreams = new ArrayList<>();
      for (int m = 0; m < members.size(); m++) {
        var codesStream = V2IntegerStream.of(candidate.recode(codes.get(m)), false, false, codecs);
        size += codesStream.size(members.get(m).count());
        codesStreams.add(codesStream);
      }
      if (size < smallest) {
        smallest = size;
        smallestDictionary = candidate;
        smallestCodes = codesStreams;
      }
    }

    columns.writeByte((smallestDictionary.isFsst() ? 1 : 0) << 4 | TYPE_SHARED_DICTIONARY);
    columns.writeString(group.prefix().getBytes(StandardCharsets.UTF_8));
    columns.writeVarint(members.size());
    smallestDictionary.writeTo(columns, NO_IMPLIED_COUNT);
    for (int m = 0; m < members.size(); m++) {
      PropertyColumn member = members.get(m);
      columns.writeByte(presence.nibble() << 4 | TYPE_STRING);
      columns.writeString(group.suffix(member).getBytes(StandardCharsets.UTF_8));
      presence.writeInline(columns);
      // Decoders read a member's codes as a string stream of the dictionary layout.
      smallestCodes.get(m).writeTo(columns, member.count(), STRINGS_DICTIONARY);
    }
  }

  private void writeInts(ByteWriter out, LongList values, int impliedCount) {
    V2IntegerStream.of(values, false, false, codecs).writeTo(out, impliedCount, 0);
  }

  /**
   * The presence of every nullable column, in the order columns are written. A presence field that
   * several columns have in common is written once, after the layout byte, and those columns
   * reference it (v2 spec, "Shared Presence Fields"). Every other one is written inline.
   */
  private static final class Presence {

    /** Per column, in write order: its presence, or null when every feature has a value. */
    private final List<Bits> byColumn = new ArrayList<>();

    /** Per column: its shared field, or -1. */
    private final List<Integer> sharedIndex = new ArrayList<>();

    private final List<Bits> sharedFields = new ArrayList<>();
    private int next;

    Presence(Layer layer, List<ColumnGroup> groups) {
      int featureCount = layer.featureCount();
      if (layer.ids.count() > 0) {
        Bits ids = null;
        if (layer.ids.count() < featureCount) {
          ids = new Bits();
          layer.ids.presence(featureCount, ids);
        }
        byColumn.add(ids);
      }
      for (ColumnGroup group : groups) {
        for (PropertyColumn column : group.members()) {
          Bits present = null;
          if (column.count() < featureCount) {
            present = new Bits();
            column.presence(featureCount, present);
          }
          byColumn.add(present);
        }
      }
      // Shared fields are ordered by the first column that references each, and only shared when
      // more than one column references them (both SHOULDs of the spec).
      List<Bits> distinct = new ArrayList<>();
      List<Integer> references = new ArrayList<>();
      List<Integer> distinctIndex = new ArrayList<>();
      for (Bits present : byColumn) {
        int index = present == null ? -1 : indexOf(distinct, present);
        if (present != null && index < 0) {
          index = distinct.size();
          distinct.add(present);
          references.add(0);
        }
        if (index >= 0) {
          references.set(index, references.get(index) + 1);
        }
        distinctIndex.add(index);
      }
      List<Integer> sharedByDistinct = new ArrayList<>();
      for (int d = 0; d < distinct.size(); d++) {
        boolean shared = references.get(d) > 1 && sharedFields.size() < MAX_SHARED_PRESENCE;
        sharedByDistinct.add(shared ? sharedFields.size() : -1);
        if (shared) {
          sharedFields.add(distinct.get(d));
        }
      }
      for (int index : distinctIndex) {
        sharedIndex.add(index < 0 ? -1 : sharedByDistinct.get(index));
      }
    }

    private static int indexOf(List<Bits> distinct, Bits present) {
      for (int i = 0; i < distinct.size(); i++) {
        if (distinct.get(i).sameAs(present)) {
          return i;
        }
      }
      return -1;
    }

    /** Writes the layout byte, then the shared presence fields. */
    void writeSharedFields(ByteWriter out, int layout) {
      boolean encodingFlag = false;
      for (Bits field : sharedFields) {
        encodingFlag |= V2Bits.bestEncoding(field) != V2Bits.BITMAP;
      }
      out.writeByte((encodingFlag ? 0x80 : 0) | sharedFields.size() << 4 | layout);
      for (Bits field : sharedFields) {
        int encoding = V2Bits.bestEncoding(field);
        if (encodingFlag) {
          out.writeByte(encoding << 4);
        }
        V2Bits.writePayload(out, field, encoding);
      }
    }

    /**
     * The presence nibble of the next column: 0 when every feature has a value, 1 to 3 for an
     * inline field in encoding {@code nibble - 1}, 4 to 10 for shared field {@code nibble - 4}.
     */
    int nibble() {
      Bits present = byColumn.get(next);
      if (present == null) {
        return 0;
      }
      int shared = sharedIndex.get(next);
      return shared >= 0 ? 4 + shared : 1 + V2Bits.bestEncoding(present);
    }

    /** Writes the inline presence field of the next column, if it has one, and moves past it. */
    void writeInline(ByteWriter out) {
      Bits present = byColumn.get(next);
      if (present != null && sharedIndex.get(next) < 0) {
        V2Bits.writePayload(out, present, V2Bits.bestEncoding(present));
      }
      next++;
    }
  }
}
