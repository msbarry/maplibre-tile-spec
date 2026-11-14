package org.maplibre.mlt.encoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes vector tiles to MLT v1, one layer and feature at a time, from MVT geometry commands.
 *
 * {@snippet :
 * var encoder = new MltEncoder();
 * encoder.beginLayer("pois", 4096);
 * int name = encoder.propertyKey("name");
 * encoder.addFeature(MvtGeometryType.POINT, mvtCommands, 1);
 * encoder.setProperty(name, "Cafe");
 * byte[] tile = encoder.encode();
 * }
 *
 * <p>Each layer is written as soon as the next one begins, and the encoder keeps its buffers for
 * the next tile, so reuse one instance per thread. Instances are not thread-safe.
 *
 * <p>Streams use the v1 encodings that need no extension: VarInt words with whichever of none,
 * delta, RLE and delta-RLE is smallest, componentwise-delta vertices, and plain or dictionary
 * strings, whichever is smaller. Output depends only on the input, so it is deterministic.
 */
public final class MltEncoder {

  private static final int LAYER_TAG = 1;
  private static final int TYPE_ID = 0;
  private static final int TYPE_LONG_ID = 2;
  private static final int TYPE_GEOMETRY = 4;
  private static final int PRESENT_STREAM = 0x00;
  private static final int DATA_STREAM = 0x10;

  /**
   * The size of one layer of the last encoded tile: {@code bytes} for the whole layer frame, and
   * {@code propertyBytes} for the metadata and data of its property columns.
   */
  public record LayerSize(String name, int bytes, int propertyBytes) {}

  private final ByteWriter tile = new ByteWriter();
  private final ByteWriter meta = new ByteWriter();
  private final ByteWriter data = new ByteWriter();
  private final Codecs codecs;
  private final GeometryColumn geometry;
  private final LongList ids = new LongList();
  private final BooleanStream idPresent = new BooleanStream();
  private final Map<String, Integer> keys = new HashMap<>();
  private final List<PropertyColumn> columns = new ArrayList<>();
  private final List<LayerSize> layerSizes = new ArrayList<>();
  private List<LayerSize> lastLayerSizes = List.of();
  private String layerName;
  private int extent;
  private int featureCount;
  private boolean idsFitIn32Bits = true;

  /** Encodings an encoder may use beyond the ones every v1 decoder must support. */
  public enum OptionalEncoding {
    /** SIMD-FastPFOR words for integer and vertex streams of 256 values or more, where smaller. */
    FASTPFOR,
    /** FSST-compressed string dictionaries, where smaller. */
    FSST
  }

  /**
   * Creates an encoder that may also use {@code encodings}. Its buffers grow to the largest tile it
   * encodes.
   */
  public MltEncoder(OptionalEncoding... encodings) {
    var enabled = List.of(encodings);
    codecs =
        new Codecs(
            enabled.contains(OptionalEncoding.FASTPFOR), enabled.contains(OptionalEncoding.FSST));
    geometry = new GeometryColumn(codecs);
  }

  /**
   * Starts a new layer, which the following features go into.
   *
   * @throws IllegalArgumentException if the name is empty or the extent is not positive
   */
  public void beginLayer(String name, int extent) {
    if (name.isEmpty() || extent <= 0) {
      throw new IllegalArgumentException("invalid layer name '" + name + "' or extent " + extent);
    }
    finishLayer();
    this.layerName = name;
    this.extent = extent;
  }

  /** Returns the key to set property {@code name} of features in the current layer with. */
  public int propertyKey(String name) {
    requireLayer();
    Integer key = keys.get(name);
    if (key == null) {
      key = columns.size();
      keys.put(name, key);
      columns.add(new PropertyColumn(name));
    }
    return key;
  }

  /**
   * Adds a feature without an id to the current layer.
   *
   * @param commands the feature's geometry as an MVT command stream (MVT spec section 4.3)
   * @throws IllegalArgumentException if the commands are malformed or hold no vertex, in which case
   *     the feature is not added
   */
  public void addFeature(MvtGeometryType type, int[] commands) {
    requireLayer();
    geometry.add(type, commands);
    idPresent.add(false);
    featureCount++;
  }

  /**
   * Adds a feature with an id (read as unsigned) to the current layer.
   *
   * @see #addFeature(MvtGeometryType, int[])
   */
  public void addFeature(MvtGeometryType type, int[] commands, long id) {
    requireLayer();
    geometry.add(type, commands);
    ids.add(id);
    idPresent.add(true);
    idsFitIn32Bits &= (id >>> 32) == 0;
    featureCount++;
  }

  /**
   * Sets a property of the latest feature: a {@link String}, {@link Boolean}, {@link Integer},
   * {@link Long}, {@link Short}, {@link Byte}, {@link Float} or {@link Double}. Other values are
   * stored as their {@link String#valueOf string}, and {@code null} leaves the property unset.
   */
  public void setProperty(int key, Object value) {
    if (featureCount == 0) {
      throw new IllegalStateException("no feature to set a property on");
    }
    columns.get(key).set(featureCount - 1, value);
  }

  /** Sets a property of the latest feature by name, see {@link #setProperty(int, Object)}. */
  public void setProperty(String key, Object value) {
    setProperty(propertyKey(key), value);
  }

  /** Returns the tile with every layer since the last call, and starts the next tile. */
  public byte[] encode() {
    finishLayer();
    byte[] result = tile.toByteArray();
    tile.clear();
    lastLayerSizes = List.copyOf(layerSizes);
    layerSizes.clear();
    return result;
  }

  /** Returns the size of each non-empty layer of the tile {@link #encode} returned last. */
  public List<LayerSize> lastLayerSizes() {
    return lastLayerSizes;
  }

  private void requireLayer() {
    if (layerName == null) {
      throw new IllegalStateException("no layer, call beginLayer first");
    }
  }

  /** Appends the current layer to the tile, unless it has no features, and clears it. */
  private void finishLayer() {
    if (featureCount > 0) {
      writeLayer();
    }
    layerName = null;
    featureCount = 0;
    geometry.clear();
    ids.clear();
    idPresent.clear();
    idsFitIn32Bits = true;
    keys.clear();
    columns.clear();
  }

  private void writeLayer() {
    meta.clear();
    data.clear();
    int columnCount = 1 + columns.size();
    if (ids.size() > 0) {
      columnCount++;
      boolean nullable = ids.size() < featureCount;
      int type = idsFitIn32Bits ? TYPE_ID : TYPE_LONG_ID;
      meta.writeByte(nullable ? type + 1 : type);
      if (nullable) {
        idPresent.writeTo(data, PRESENT_STREAM);
      }
      IntegerStream.of(ids, false, !idsFitIn32Bits, codecs).writeTo(data, DATA_STREAM);
    }
    meta.writeByte(TYPE_GEOMETRY);
    geometry.writeTo(data);
    int beforeProperties = meta.size() + data.size();
    for (PropertyColumn column : columns) {
      column.writeTo(meta, data, featureCount, codecs);
    }
    int propertyBytes = meta.size() + data.size() - beforeProperties;

    byte[] name = layerName.getBytes(StandardCharsets.UTF_8);
    int frameSize =
        1
            + ByteWriter.varintSize(name.length)
            + name.length
            + ByteWriter.varintSize(extent)
            + ByteWriter.varintSize(columnCount)
            + meta.size()
            + data.size();
    int start = tile.size();
    tile.writeVarint(frameSize);
    tile.writeByte(LAYER_TAG);
    tile.writeString(name);
    tile.writeVarint(extent);
    tile.writeVarint(columnCount);
    tile.writeBytes(meta);
    tile.writeBytes(data);
    layerSizes.add(new LayerSize(layerName, tile.size() - start, propertyBytes));
  }
}
