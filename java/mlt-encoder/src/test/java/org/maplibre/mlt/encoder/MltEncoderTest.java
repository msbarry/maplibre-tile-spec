package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import org.maplibre.mlt.data.Feature;
import org.maplibre.mlt.data.Layer;
import org.maplibre.mlt.decoder.MltDecoder;

/** Encodes tiles and decodes them with the mlt-core decoder. */
class MltEncoderTest {

  private final MltEncoder encoder = newEncoder();

  MltEncoder newEncoder() {
    return new MltEncoder();
  }

  @Test
  void point() throws IOException {
    encoder.beginLayer("layer1", 64);
    encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(13, 42).build(), 100);
    byte[] tile = encoder.encode();
    // Byte for byte the reference fixture test/synthetic/0x01/id.mlt.
    assertArrayEquals(hex("1d01066c6179657231400200041002010164023002010100134202021a54"), tile);
    assertEquals(List.of("100 POINT (13 42) {}"), decodeLayer(tile, "layer1"));
  }

  @Test
  void optionalIds() throws IOException {
    encoder.beginLayer("layer1", 64);
    int[] point = new Commands().moveTo(13, 42).build();
    encoder.addFeature(MvtGeometryType.POINT, point, 100);
    encoder.addFeature(MvtGeometryType.POINT, point, 101);
    encoder.addFeature(MvtGeometryType.POINT, point);
    encoder.addFeature(MvtGeometryType.POINT, point, 105);
    encoder.addFeature(MvtGeometryType.POINT, point, 106);
    assertEquals(
        List.of(
            "100 POINT (13 42) {}",
            "101 POINT (13 42) {}",
            "- POINT (13 42) {}",
            "105 POINT (13 42) {}",
            "106 POINT (13 42) {}"),
        decodeLayer(encoder.encode(), "layer1"));
  }

  @Test
  void longIds() throws IOException {
    encoder.beginLayer("layer1", 4096);
    int[] point = new Commands().moveTo(1, 2).build();
    encoder.addFeature(MvtGeometryType.POINT, point, 1L << 40);
    encoder.addFeature(MvtGeometryType.POINT, point, 3);
    assertEquals(
        List.of("1099511627776 POINT (1 2) {}", "3 POINT (1 2) {}"),
        decodeLayer(encoder.encode(), "layer1"));
  }

  @Test
  void everyGeometryTypeInOneLayer() throws IOException {
    encoder.beginLayer("mixed", 4096);
    addEveryGeometryType();
    assertEquals(EVERY_GEOMETRY_TYPE, decodeLayer(encoder.encode(), "mixed"));
  }

  @Test
  void eachGeometryTypeAlone() throws IOException {
    for (int i = 0; i < EVERY_GEOMETRY_TYPE.size(); i++) {
      encoder.beginLayer("layer", 4096);
      addGeometry(i);
      assertEquals(List.of(EVERY_GEOMETRY_TYPE.get(i)), decodeLayer(encoder.encode(), "layer"));
    }
  }

  @Test
  void eachPairOfGeometryTypes() throws IOException {
    for (int a = 0; a < EVERY_GEOMETRY_TYPE.size(); a++) {
      for (int b = 0; b < EVERY_GEOMETRY_TYPE.size(); b++) {
        encoder.beginLayer("layer", 4096);
        addGeometry(a);
        addGeometry(b);
        addGeometry(a);
        assertEquals(
            List.of(
                EVERY_GEOMETRY_TYPE.get(a), EVERY_GEOMETRY_TYPE.get(b), EVERY_GEOMETRY_TYPE.get(a)),
            decodeLayer(encoder.encode(), "layer"),
            a + " and " + b);
      }
    }
  }

  @Test
  void propertiesOfEveryType() throws IOException {
    encoder.beginLayer("props", 4096);
    int[] point = new Commands().moveTo(1, 1).build();
    encoder.addFeature(MvtGeometryType.POINT, point);
    encoder.setProperty("bool", true);
    encoder.setProperty("int", 1);
    encoder.setProperty("long", 1L << 40);
    encoder.setProperty("float", 1.5f);
    encoder.setProperty("double", 2.5);
    encoder.setProperty("string", "a");
    encoder.setProperty("intAndDouble", 1);
    encoder.setProperty("intAndString", 1);
    encoder.addFeature(MvtGeometryType.POINT, point);
    encoder.setProperty("bool", false);
    encoder.setProperty("int", -2);
    encoder.setProperty("long", -3L);
    encoder.setProperty("float", -1f);
    encoder.setProperty("double", -2d);
    encoder.setProperty("string", "b");
    encoder.setProperty("intAndDouble", 0.5);
    encoder.setProperty("intAndString", "x");
    encoder.addFeature(MvtGeometryType.POINT, point);
    encoder.setProperty("other", null);
    assertEquals(
        List.of(
            "- POINT (1 1) {bool=true, double=2.5, float=1.5, int=1, intAndDouble=1.0, intAndString=1, long=1099511627776, string=a}",
            "- POINT (1 1) {bool=false, double=-2.0, float=-1.0, int=-2, intAndDouble=0.5, intAndString=x, long=-3, string=b}",
            "- POINT (1 1) {}"),
        decodeLayer(encoder.encode(), "props"));
  }

  @Test
  void plainAndDictionaryStrings() throws IOException {
    encoder.beginLayer("strings", 4096);
    int[] point = new Commands().moveTo(1, 1).build();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      encoder.addFeature(MvtGeometryType.POINT, point);
      encoder.setProperty("unique", "name " + i);
      encoder.setProperty("repeated", i % 3 == 0 ? "residential" : "commercial");
      if (i % 7 != 0) {
        encoder.setProperty("sparse", "école " + (i % 2));
      }
      expected.add(
          "- POINT (1 1) {repeated="
              + (i % 3 == 0 ? "residential" : "commercial")
              + (i % 7 != 0 ? ", sparse=école " + (i % 2) : "")
              + ", unique=name "
              + i
              + "}");
    }
    assertEquals(expected, decodeLayer(encoder.encode(), "strings"));
  }

  @Test
  void manyFeaturesUseRunsAndDeltas() throws IOException {
    encoder.beginLayer("many", 4096);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(i, 2 * i).build(), i + 10);
      encoder.setProperty("rank", i / 100);
      encoder.setProperty("sequence", i);
      expected.add(
          (i + 10) + " POINT (" + i + " " + 2 * i + ") {rank=" + i / 100 + ", sequence=" + i + "}");
    }
    assertEquals(expected, decodeLayer(encoder.encode(), "many"));
  }

  @Test
  void largeLayerOfEveryKindOfStream() throws IOException {
    List<String> expected = addLargeLayer(encoder);
    assertEquals(expected, decodeLayer(encoder.encode(), "large"));
  }

  @Test
  void largeStringColumns() throws IOException {
    List<String> expected = addStreetNames(encoder);
    assertEquals(expected, decodeLayer(encoder.encode(), "streets"));
  }

  /** Adds a layer with 256 values or more in each kind of stream, and returns what decodes. */
  static List<String> addLargeLayer(MltEncoder encoder) {
    encoder.beginLayer("large", 4096);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      int x = (i * 37) % 4096;
      int y = (i * 101) % 4096;
      long id = 1000L + i * 3L;
      // Mostly small values with rare large ones, the shape FastPFOR exceptions are for.
      int outlier = i % 97 == 0 ? 1_000_000 + i : i % 5;
      String properties = "{outlier=" + outlier + ", rank=" + (i % 10) + "}";
      if (i % 3 == 0) {
        int[][] line = {{x, y}, {x + 5, y + outlier % 7}, {x + 9, y}};
        encoder.addFeature(MvtGeometryType.LINESTRING, new Commands().line(line).build(), id);
        expected.add(
            String.format(
                "%d LINESTRING (%d %d, %d %d, %d %d) %s",
                id, x, y, x + 5, y + outlier % 7, x + 9, y, properties));
      } else {
        encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(x, y).build(), id);
        expected.add(String.format("%d POINT (%d %d) %s", id, x, y, properties));
      }
      encoder.setProperty("outlier", outlier);
      encoder.setProperty("rank", i % 10);
    }
    return expected;
  }

  /** Adds a layer with string columns big and repetitive enough for FSST to pay off. */
  static List<String> addStreetNames(MltEncoder encoder) {
    encoder.beginLayer("streets", 4096);
    String[] suffixes = {"Street", "Avenue", "Road", "Boulevard"};
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      String name = "North " + (i * 7919 % 1000) + "th " + suffixes[i % 4];
      encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(i, i).build());
      encoder.setProperty("name", name);
      if (i % 4 != 0) {
        encoder.setProperty("name:fr", "Rue " + name);
      }
      expected.add(
          "- POINT ("
              + i
              + " "
              + i
              + ") {name="
              + name
              + (i % 4 != 0 ? ", name:fr=Rue " + name : "")
              + "}");
    }
    return expected;
  }

  @Test
  void severalLayersAndEmptyLayersSkipped() throws IOException {
    encoder.beginLayer("empty", 4096);
    encoder.beginLayer("a", 4096);
    encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(1, 2).build());
    encoder.setProperty("key", "a");
    encoder.beginLayer("b", 512);
    encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(3, 4).build());
    encoder.setProperty("key", 1);
    byte[] tile = encoder.encode();
    assertEquals(List.of("- POINT (1 2) {key=a}"), decodeLayer(tile, "a"));
    assertEquals(List.of("- POINT (3 4) {key=1}"), decodeLayer(tile, "b"));
    assertEquals(
        List.of("a", "b"),
        encoder.lastLayerSizes().stream().map(MltEncoder.LayerSize::name).toList());
    assertEquals(
        tile.length, encoder.lastLayerSizes().stream().mapToInt(MltEncoder.LayerSize::bytes).sum());
  }

  @Test
  void encoderIsReusable() throws IOException {
    encoder.beginLayer("layer", 4096);
    addEveryGeometryType();
    byte[] first = encoder.encode();
    encoder.beginLayer("layer", 4096);
    addEveryGeometryType();
    assertArrayEquals(first, encoder.encode());
  }

  @Test
  void malformedGeometryIsRejectedWithoutSideEffects() throws IOException {
    encoder.beginLayer("layer", 4096);
    addGeometry(0);
    int[] unclosedRing = new Commands().moveTo(0, 0).lineTo(10, 0).lineTo(10, 10).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> encoder.addFeature(MvtGeometryType.POLYGON, unclosedRing));
    int[] truncated = {9, 2};
    assertThrows(
        IllegalArgumentException.class,
        () -> encoder.addFeature(MvtGeometryType.LINESTRING, truncated));
    int[] empty = {};
    assertThrows(
        IllegalArgumentException.class, () -> encoder.addFeature(MvtGeometryType.POINT, empty));
    addGeometry(1);
    assertEquals(
        List.of(EVERY_GEOMETRY_TYPE.get(0), EVERY_GEOMETRY_TYPE.get(1)),
        decodeLayer(encoder.encode(), "layer"));
  }

  @Test
  void jtsGeometries() throws Exception {
    var wkt = new WKTReader();
    encoder.beginLayer("layer", 4096);
    for (String expected : EVERY_GEOMETRY_TYPE) {
      encoder.addFeature(wkt.read(expected.substring(2, expected.length() - 3)));
    }
    assertEquals(EVERY_GEOMETRY_TYPE, decodeLayer(encoder.encode(), "layer"));
  }

  @Test
  void jtsPolygonsAreRewound() throws Exception {
    var wkt = new WKTReader();
    encoder.beginLayer("layer", 4096);
    for (String expected : EVERY_GEOMETRY_TYPE.subList(4, 6)) {
      encoder.addFeature(wkt.read(expected.substring(2, expected.length() - 3)).reverse());
    }
    String rewound = decodeLayer(encoder.encode(), "layer").toString();
    // The same rings as the originals, winding the same way, each starting at another vertex.
    assertEquals(
        "[- POLYGON ((10 0, 10 10, 0 10, 0 0, 10 0), (2 4, 4 4, 4 2, 2 2, 2 4)) {}, "
            + "- MULTIPOLYGON (((10 0, 10 10, 0 10, 0 0, 10 0), (2 4, 4 4, 4 2, 2 2, 2 4)), "
            + "((30 20, 30 30, 20 20, 30 20))) {}]",
        rewound);
  }

  @Test
  void jtsCollectionOfMixedKindsIsOneFeaturePerKind() throws Exception {
    encoder.beginLayer("layer", 4096);
    encoder.addFeature(
        new WKTReader().read("GEOMETRYCOLLECTION (POINT (1.4 2.6), LINESTRING (0 0, 10 0))"), 5);
    encoder.setProperty("kind", "mixed");
    assertEquals(
        List.of(
            "5 MULTIPOINT ((1 3)) {kind=mixed}", "5 MULTILINESTRING ((0 0, 10 0)) {kind=mixed}"),
        decodeLayer(encoder.encode(), "layer"));
  }

  @Test
  void featuresSortedById() throws IOException {
    var sorted = MltEncoder.builder().featureOrders(FeatureOrder.ID).build();
    sorted.beginLayer("layer", 4096);
    for (long id : new long[] {3, 1, 2}) {
      sorted.addFeature(MvtGeometryType.POINT, new Commands().moveTo((int) id, 0).build(), id);
      sorted.setProperty("id", id);
    }
    sorted.addFeature(MvtGeometryType.POINT, new Commands().moveTo(9, 9).build());
    assertEquals(
        List.of(
            "1 POINT (1 0) {id=1}",
            "2 POINT (2 0) {id=2}",
            "3 POINT (3 0) {id=3}",
            "- POINT (9 9) {}"),
        decodeLayer(sorted.encode(), "layer"));
  }

  @Test
  void featuresSortedAlongHilbertCurveKeepTheirValues() throws IOException {
    var sorted = MltEncoder.builder().featureOrders(FeatureOrder.HILBERT).build();
    List<String> expected = addLargeLayer(sorted);
    List<String> actual = decodeLayer(sorted.encode(), "large");
    assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
  }

  @Test
  void smallestOfSeveralOrdersIsKept() {
    var unsorted = MltEncoder.builder().build();
    addLargeLayer(unsorted);
    var trial =
        MltEncoder.builder().featureOrders(FeatureOrder.INPUT, FeatureOrder.HILBERT).build();
    addLargeLayer(trial);
    assertTrue(trial.encode().length <= unsorted.encode().length);
  }

  @Test
  void sharedDictionaryPerLayer() throws IOException {
    var shared = MltEncoder.builder().sharedDictionaries(SharedDictionaries.PER_LAYER).build();
    List<String> expected = addTranslatedNames(shared);
    byte[] tile = shared.encode();
    assertEquals(expected, decodeLayer(tile, "places"));
    var separate = new MltEncoder();
    addTranslatedNames(separate);
    assertTrue(tile.length < separate.encode().length);
  }

  /** Adds name columns that mostly repeat each other, as translations of place names do. */
  private static List<String> addTranslatedNames(MltEncoder encoder) {
    encoder.beginLayer("places", 4096);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      String name = "Place number " + i;
      String english = i % 5 == 0 ? "Place " + i + " (en)" : name;
      encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(i, i).build());
      encoder.setProperty("name", name);
      encoder.setProperty("name:en", english);
      if (i % 3 != 0) {
        encoder.setProperty("name:de", name);
      }
      expected.add(
          "- POINT ("
              + i
              + " "
              + i
              + ") {name="
              + name
              + (i % 3 != 0 ? ", name:de=" + name : "")
              + ", name:en="
              + english
              + "}");
    }
    return expected;
  }

  @Test
  void tessellatedPolygonsDecodeToTheirRings() throws IOException {
    var tessellating = MltEncoder.builder().tessellation(Tessellation.WITH_OUTLINES).build();
    tessellating.beginLayer("mixed", 4096);
    var wkt = new WKTReader();
    for (String expected : EVERY_GEOMETRY_TYPE) {
      try {
        tessellating.addFeature(wkt.read(expected.substring(2, expected.length() - 3)));
      } catch (org.locationtech.jts.io.ParseException e) {
        throw new IllegalStateException(e);
      }
    }
    assertEquals(EVERY_GEOMETRY_TYPE, decodeLayer(tessellating.encode(), "mixed"));
  }

  private static final List<String> EVERY_GEOMETRY_TYPE =
      List.of(
          "- POINT (1 2) {}",
          "- MULTIPOINT ((1 2), (3 4)) {}",
          "- LINESTRING (0 0, 10 0, 10 10) {}",
          "- MULTILINESTRING ((0 0, 5 5), (10 10, 20 20, 30 10)) {}",
          "- POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2)) {}",
          "- MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2)), ((20 20, 30 20, 30 30, 20 20))) {}");

  private void addEveryGeometryType() {
    for (int i = 0; i < EVERY_GEOMETRY_TYPE.size(); i++) {
      addGeometry(i);
    }
  }

  private void addGeometry(int index) {
    var exterior = new int[][] {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
    var hole = new int[][] {{2, 2}, {2, 4}, {4, 4}, {4, 2}};
    switch (index) {
      case 0 -> encoder.addFeature(MvtGeometryType.POINT, new Commands().moveTo(1, 2).build());
      case 1 ->
          encoder.addFeature(
              MvtGeometryType.POINT, new Commands().moveTo(1, 2).moveTo(3, 4).build());
      case 2 ->
          encoder.addFeature(
              MvtGeometryType.LINESTRING,
              new Commands().line(new int[][] {{0, 0}, {10, 0}, {10, 10}}).build());
      case 3 ->
          encoder.addFeature(
              MvtGeometryType.LINESTRING,
              new Commands()
                  .line(new int[][] {{0, 0}, {5, 5}})
                  .line(new int[][] {{10, 10}, {20, 20}, {30, 10}})
                  .build());
      case 4 ->
          encoder.addFeature(
              MvtGeometryType.POLYGON, new Commands().ring(exterior).ring(hole).build());
      default ->
          encoder.addFeature(
              MvtGeometryType.POLYGON,
              new Commands()
                  .ring(exterior)
                  .ring(hole)
                  .ring(new int[][] {{20, 20}, {30, 20}, {30, 30}})
                  .build());
    }
  }

  /** Decodes one layer to "id geometry {properties}" per feature. */
  private static List<String> decodeLayer(byte[] tile, String name) throws IOException {
    Layer layer =
        MltDecoder.decodeMlTile(tile)
            .getLayerStream(false)
            .filter(l -> l.name().equals(name))
            .findFirst()
            .orElseThrow();
    List<String> result = new ArrayList<>();
    int index = 0;
    for (Feature feature : layer.features()) {
      Map<String, Object> properties = new LinkedHashMap<>();
      int featureIndex = index++;
      for (var property : feature.getProperties()) {
        Object value = property.getValue(featureIndex);
        if (value != null) {
          properties.put(property.getName(), value);
        }
      }
      String sortedProperties =
          properties.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(", ", "{", "}"));
      result.add(
          (feature.hasId() ? Long.toString(feature.getId()) : "-")
              + " "
              + feature.getGeometry().toText()
              + " "
              + sortedProperties);
    }
    return result;
  }

  private static byte[] hex(String hex) {
    byte[] result = new byte[hex.length() / 2];
    for (int i = 0; i < result.length; i++) {
      result[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return result;
  }

  /** Builds MVT command streams from absolute coordinates. */
  static final class Commands {
    private final List<Integer> commands = new ArrayList<>();
    private int x;
    private int y;

    Commands moveTo(int x, int y) {
      commands.add(1 | (1 << 3));
      return vertex(x, y);
    }

    Commands lineTo(int x, int y) {
      commands.add(2 | (1 << 3));
      return vertex(x, y);
    }

    Commands line(int[][] points) {
      moveTo(points[0][0], points[0][1]);
      commands.add(2 | ((points.length - 1) << 3));
      for (int i = 1; i < points.length; i++) {
        vertex(points[i][0], points[i][1]);
      }
      return this;
    }

    Commands ring(int[][] points) {
      line(points);
      commands.add(7 | (1 << 3));
      return this;
    }

    int[] build() {
      return commands.stream().mapToInt(Integer::intValue).toArray();
    }

    private Commands vertex(int newX, int newY) {
      commands.add(zigzag(newX - x));
      commands.add(zigzag(newY - y));
      x = newX;
      y = newY;
      return this;
    }

    private static int zigzag(int value) {
      return (value << 1) ^ (value >> 31);
    }
  }
}
