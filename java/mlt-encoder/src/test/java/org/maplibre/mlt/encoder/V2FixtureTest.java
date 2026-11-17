package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.io.geojson.GeoJsonReader;

/**
 * Encodes the inputs of the Rust reference encoder's MLT v2 fixtures ({@code test/synthetic/0x02})
 * and checks the output is byte for byte the same. There is no v2 decoder in Java yet, so this is
 * how v2 output is tested here. The fixtures listed are the ones whose input the JSON fully
 * describes and where both encoders pick the same encodings.
 */
class V2FixtureTest {

  private static final Path FIXTURES = Path.of("../../test/synthetic/0x02");

  /**
   * The fixtures both encoders write the same way. The others differ by design: their names force
   * an encoding (delta, RLE, FastPFOR, Morton, rANS), a type (UInt, Int8, f32) or a nullable column
   * without nulls, they use what this encoder leaves out (m-values, Z, nested properties), or this
   * encoder finds a smaller encoding (bit-packed lengths and tree symbols, RLE ids, empty streams
   * without a byte length). Those encodings are checked by decoding every tile of a real extract
   * with the Rust decoder instead.
   */
  private static final String SAME_AS_REFERENCE =
      """
      bool_runs bool_sparse extent_131072 extent_32768 extent_4096 extent_512 extent_buf_131072
      extent_buf_32768 extent_buf_4096 extent_buf_512 id id64 id64_max_delta id_max_delta id_min
      ids64_minmax_delta ids64_opt ids64_rle ids_opt ids_rle line line_morton_curve_no_morton
      line_zero_length mix_2_line_line mix_2_line_mpt mix_2_line_poly mix_2_mpt_mline
      mix_2_mpt_mpt mix_2_poly_mpt mix_2_poly_poly mix_2_polyh_mpt mix_2_pt_line mix_2_pt_mline
      mix_2_pt_mpt mix_2_pt_poly mix_2_pt_polyh mix_2_pt_pt mix_3_pt_mline_pt mix_3_pt_mpt_mline
      multiline multipoint point poly poly_collinear poly_hole poly_hole_touching
      poly_morton_ring_no_morton poly_self_intersect prop_bool_false_np prop_bool_false_null
      prop_bool_np prop_bool_null_false prop_bool_null_true prop_bool_true_null
      prop_empty_name_np prop_f64_max_np prop_f64_min_norm_np prop_f64_min_val_np
      prop_f64_neg_zero_np prop_f64_np prop_f64_null_val prop_f64_val_null prop_f64_zero_np
      prop_i32_neg_np prop_i32_np prop_i32_null_val prop_i32_plain_np prop_i32_val_null
      prop_i64_neg_np prop_i64_np prop_i64_null_val prop_i64_plain_np prop_i64_val_null
      prop_special_name_np prop_str_ascii_np prop_str_empty_np prop_str_empty_val
      prop_str_escape_np prop_str_null_val prop_str_special_np prop_str_unicode_np
      prop_str_val_empty prop_str_val_null props_i32_rle_np props_str_plain_nulls
      """;

  static Stream<String> fixtures() {
    return Stream.of(SAME_AS_REFERENCE.trim().split("\\s+"));
  }

  @ParameterizedTest
  @MethodSource("fixtures")
  void matchesReferenceEncoder(String fixture) throws Exception {
    byte[] expected = Files.readAllBytes(FIXTURES.resolve(fixture + ".mlt"));
    byte[] actual = encode(fixture);
    assertEquals(HexFormat.of().formatHex(expected), HexFormat.of().formatHex(actual));
  }

  private static byte[] encode(String fixture) throws Exception {
    var encoder =
        MltEncoder.builder()
            .version(MltVersion.V2)
            .fastPfor(fixture.contains("fpf"))
            .fsst(fixture.contains("fsst"))
            .sharedDictionaries(
                fixture.contains("shared_dict")
                    ? SharedDictionaries.PER_LAYER
                    : SharedDictionaries.NONE)
            .tessellation(
                fixture.endsWith("_tri")
                    ? Tessellation.TRIANGLES_ONLY
                    : fixture.contains("_tes") ? Tessellation.WITH_OUTLINES : Tessellation.NONE)
            .build();
    var json = JsonParser.parseString(Files.readString(FIXTURES.resolve(fixture + ".json")));
    var reader = new GeoJsonReader();
    String layer = null;
    for (JsonElement element : json.getAsJsonObject().getAsJsonArray("features")) {
      JsonObject feature = element.getAsJsonObject();
      JsonObject properties = feature.getAsJsonObject("properties");
      String name = properties.get("_layer").getAsString();
      if (!name.equals(layer)) {
        encoder.beginLayer(name, properties.get("_extent").getAsInt());
        layer = name;
      }
      var geometry = reader.read(feature.get("geometry").toString());
      if (feature.has("id")) {
        encoder.addFeature(geometry, feature.get("id").getAsLong());
      } else {
        encoder.addFeature(geometry);
      }
      for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
        if (!property.getKey().startsWith("_") && !property.getValue().isJsonNull()) {
          encoder.setProperty(property.getKey(), value(property.getValue()));
        }
      }
    }
    return encoder.encode();
  }

  private static Object value(JsonElement value) {
    var primitive = value.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    } else if (primitive.isString()) {
      return primitive.getAsString();
    }
    double number = primitive.getAsDouble();
    if (number == Math.rint(number) && !primitive.getAsString().contains(".")) {
      long whole = primitive.getAsLong();
      return whole == (int) whole ? (Object) (int) whole : (Object) whole;
    }
    return number;
  }
}
