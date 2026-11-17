package org.maplibre.mlt.encoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;

/**
 * Encodes vector tiles to MLT, one layer and feature at a time, from MVT geometry commands or JTS
 * geometries.
 *
 * {@snippet :
 * var encoder = MltEncoder.builder().fastPfor(true).fsst(true).build();
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
 * <p>Every stream is stored in whichever of the encodings the options allow takes the fewest bytes.
 * Output depends only on the input and options, so it is deterministic.
 */
public final class MltEncoder {

  /**
   * The size of one layer of the last encoded tile: {@code bytes} for the whole layer frame, and
   * {@code propertyBytes} for its property columns.
   */
  public record LayerSize(String name, int bytes, int propertyBytes) {}

  /** What the writers may do, as {@link Builder} sets it. */
  record Options(
      MltVersion version,
      boolean fastPfor,
      boolean fsst,
      SharedDictionaries sharedDictionaries,
      List<FeatureOrder> featureOrders,
      Tessellation tessellation) {}

  private final Options options;
  private final LayerWriter writer;
  private final Layer layer = new Layer();
  private final ByteWriter tile = new ByteWriter();
  private final List<LayerSize> layerSizes = new ArrayList<>();
  private List<LayerSize> lastLayerSizes = List.of();
  private boolean inLayer;

  /** The features the last {@code addFeature} call added, which properties are set on. */
  private int firstFeatureOfLastAdd;

  // Two buffers to write a layer in each feature order to, keeping the smallest.
  private ByteWriter trial = new ByteWriter();
  private ByteWriter smallestTrial = new ByteWriter();

  /** Creates an encoder with the default options: MLT v1, with only the encodings v1 requires. */
  public MltEncoder() {
    this(builder().options());
  }

  private MltEncoder(Options options) {
    this.options = options;
    var codecs = new Codecs(options.fastPfor(), options.fsst());
    this.writer =
        options.version() == MltVersion.V1
            ? new V1LayerWriter(options, codecs)
            : new V2LayerWriter(options, codecs);
  }

  /** Returns a builder that starts from the default options. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Starts a new layer, which the following features go into.
   *
   * @throws IllegalArgumentException if the name is empty or the extent is not positive, or for v2
   *     not a power of two from 64 to 2097152
   */
  public void beginLayer(String name, int extent) {
    if (name.isEmpty() || extent <= 0) {
      throw new IllegalArgumentException("invalid layer name '" + name + "' or extent " + extent);
    }
    if (options.version() == MltVersion.V2) {
      V2LayerWriter.extentCode(extent);
    }
    finishLayer();
    layer.begin(name, extent);
    inLayer = true;
  }

  /** Returns the key to set property {@code name} of features in the current layer with. */
  public int propertyKey(String name) {
    requireLayer();
    return layer.propertyKey(name);
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
    firstFeatureOfLastAdd = layer.featureCount();
    MvtCommands.add(type, commands, layer.geometries);
  }

  /**
   * Adds a feature with an id (read as unsigned) to the current layer.
   *
   * @see #addFeature(MvtGeometryType, int[])
   */
  public void addFeature(MvtGeometryType type, int[] commands, long id) {
    addFeature(type, commands);
    layer.ids.add(firstFeatureOfLastAdd, id);
  }

  /**
   * Adds a feature without an id to the current layer from a JTS geometry in tile coordinates,
   * which are rounded to the nearest integer. Polygon rings are rewound as MLT requires. A geometry
   * collection that mixes points, lines and polygons becomes one feature per kind, and the next
   * {@link #setProperty} calls set the properties of each.
   *
   * @throws IllegalArgumentException if the geometry is empty, in which case no feature is added
   */
  public void addFeature(Geometry geometry) {
    requireLayer();
    List<Geometry> features = JtsGeometries.features(geometry);
    if (features.isEmpty()) {
      throw new IllegalArgumentException("geometry has no vertices");
    }
    int featureCountBefore = layer.featureCount();
    for (Geometry feature : features) {
      JtsGeometries.add(feature, layer.geometries);
    }
    firstFeatureOfLastAdd = featureCountBefore;
  }

  /**
   * Adds a feature with an id (read as unsigned) to the current layer from a JTS geometry.
   *
   * @see #addFeature(Geometry)
   */
  public void addFeature(Geometry geometry, long id) {
    addFeature(geometry);
    for (int f = firstFeatureOfLastAdd; f < layer.featureCount(); f++) {
      layer.ids.add(f, id);
    }
  }

  /**
   * Sets a property of the latest feature: a {@link String}, {@link Boolean}, {@link Integer},
   * {@link Long}, {@link Short}, {@link Byte}, {@link Float} or {@link Double}. Other values are
   * stored as their {@link String#valueOf string}, and {@code null} leaves the property unset.
   */
  public void setProperty(int key, Object value) {
    if (layer.featureCount() == 0) {
      throw new IllegalStateException("no feature to set a property on");
    }
    PropertyColumn column = layer.columns.get(key);
    for (int f = firstFeatureOfLastAdd; f < layer.featureCount(); f++) {
      column.set(f, value);
    }
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
    if (!inLayer) {
      throw new IllegalStateException("no layer, call beginLayer first");
    }
  }

  /** Appends the current layer to the tile, unless it has no features. */
  private void finishLayer() {
    if (inLayer && layer.featureCount() > 0) {
      writeLayer();
    }
    inLayer = false;
  }

  private void writeLayer() {
    List<FeatureOrder> orders = options.featureOrders();
    if (orders.size() == 1) {
      layerSizes.add(writer.write(layer.reordered(orders.getFirst()), tile));
      return;
    }
    // Writes the layer in each order and keeps the smallest, the first on a tie.
    LayerSize smallest = null;
    for (FeatureOrder order : orders) {
      trial.clear();
      LayerSize size = writer.write(layer.reordered(order), trial);
      if (smallest == null || size.bytes() < smallest.bytes()) {
        smallest = size;
        var swap = smallestTrial;
        smallestTrial = trial;
        trial = swap;
      }
    }
    tile.writeBytes(smallestTrial);
    layerSizes.add(smallest);
  }

  /**
   * Sets the options of an {@link MltEncoder}; each defaults to what {@code new MltEncoder()} uses.
   */
  public static final class Builder {
    private MltVersion version = MltVersion.V1;
    private boolean fastPfor;
    private boolean fsst;
    private SharedDictionaries sharedDictionaries = SharedDictionaries.NONE;
    private List<FeatureOrder> featureOrders = List.of(FeatureOrder.INPUT);
    private Tessellation tessellation = Tessellation.NONE;

    private Builder() {}

    /** The MLT version to write. Default {@link MltVersion#V1}. */
    public Builder version(MltVersion version) {
      this.version = Objects.requireNonNull(version);
      return this;
    }

    /**
     * Whether integer and vertex streams may use FastPFOR, where it is smaller. Default false:
     * every decoder must support it, but it makes gzip-compressed tiles larger.
     */
    public Builder fastPfor(boolean fastPfor) {
      this.fastPfor = fastPfor;
      return this;
    }

    /**
     * Whether string dictionaries of 2 KB or more may be compressed with FSST, where it is smaller.
     * Default false: every decoder must support it, but it makes gzip-compressed tiles larger.
     */
    public Builder fsst(boolean fsst) {
      this.fsst = fsst;
      return this;
    }

    /**
     * Which string columns of a layer share one dictionary. Default {@link
     * SharedDictionaries#NONE}.
     */
    public Builder sharedDictionaries(SharedDictionaries sharedDictionaries) {
      this.sharedDictionaries = Objects.requireNonNull(sharedDictionaries);
      return this;
    }

    /**
     * The orders to try writing each layer's features in: the encoder writes the layer in each and
     * keeps the smallest, so every extra order costs another write of the layer. Default {@link
     * FeatureOrder#INPUT} only.
     *
     * @throws IllegalArgumentException if no order is given
     */
    public Builder featureOrders(FeatureOrder... featureOrders) {
      if (featureOrders.length == 0) {
        throw new IllegalArgumentException("no feature order");
      }
      this.featureOrders = List.of(featureOrders);
      return this;
    }

    /** Whether to store polygons pre-cut into triangles. Default {@link Tessellation#NONE}. */
    public Builder tessellation(Tessellation tessellation) {
      this.tessellation = Objects.requireNonNull(tessellation);
      return this;
    }

    public MltEncoder build() {
      return new MltEncoder(options());
    }

    private Options options() {
      return new Options(version, fastPfor, fsst, sharedDictionaries, featureOrders, tessellation);
    }
  }
}
