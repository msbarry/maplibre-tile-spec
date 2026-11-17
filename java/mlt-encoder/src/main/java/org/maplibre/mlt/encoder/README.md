# mlt-encoder: a lightweight MLT encoder for Java

A pure-Java encoder for MLT v1 and v2, written from scratch as a proof of concept for what a full rewrite of
the Java encoder could look like. It turns tiles into MLT directly from MVT geometry commands or JTS
geometries, with no intermediate tile model, and picks the smallest encoding of every stream from exact sizes
rather than by trial encoding.

On every tile of a Massachusetts OpenMapTiles extract, it is 2-4x faster than the native Rust encoder with the
same options, its output decodes with the Rust decoder to the same features, and it is up to 10% smaller and
never more than 0.4% larger. It is about 3,100 lines of code (4,000 with comments), with no dependencies beyond
mlt-core (FSST), JavaFastPFOR (v1 FastPFOR), earcut4j (tessellation) and JTS.

## Usage

From MVT geometry commands, the form a tile generator that already writes MVT has at hand:

```java
var encoder = MltEncoder.builder()
    .version(MltVersion.V2)
    .fastPfor(true)
    .fsst(true)
    .sharedDictionaries(SharedDictionaries.PER_LAYER)
    .featureOrders(FeatureOrder.INPUT, FeatureOrder.HILBERT) // try both, keep the smaller
    .tessellation(Tessellation.WITH_OUTLINES)
    .build();
encoder.beginLayer("pois", 4096);
encoder.addFeature(MvtGeometryType.POINT, mvtCommands, 1);
encoder.setProperty("name", "Cafe");
byte[] tile = encoder.encode();
```

From JTS geometries in tile coordinates (rounded to integers, polygon rings rewound as MLT requires). A
geometry collection that mixes points, lines and polygons becomes one feature per kind, and the properties set
after it apply to each:

```java
var factory = new GeometryFactory();
var encoder = MltEncoder.builder().version(MltVersion.V2).build(); // defaults for everything else
encoder.beginLayer("buildings", 4096);
encoder.addFeature(factory.createPolygon(new Coordinate[] {
    new Coordinate(10, 10), new Coordinate(90, 10), new Coordinate(90, 60), new Coordinate(10, 10)}), 7);
encoder.setProperty("height", 12);
encoder.setProperty("name", "Town Hall");
byte[] tile = encoder.encode();
```

One encoder per thread, reused for every tile: buffers and codec tables are kept, and the FastPFOR / FSST
tables (about 1 MB each) are only created the first time a stream is large enough to use them.

## Code walkthrough

Start at the entry point, then follow one tile through:

1. [`MltEncoder`](MltEncoder.java): the public API and its `Builder`, one tile at a time. Collects each layer,
   then hands it to the writer for the chosen version, once per feature order to try.
2. What a layer holds, independent of the MLT version:
   - [`Layer`](Layer.java): geometries, ids and property columns, plus reordering for
     [`FeatureOrder`](FeatureOrder.java) (sort keys from [`SpaceFillingCurve`](SpaceFillingCurve.java)).
   - [`Geometries`](Geometries.java): every feature's vertices as absolute coordinates in GeoArrow-style
     offset arrays (feature, part, ring, vertex), filled by [`MvtCommands`](MvtCommands.java) or
     [`JtsGeometries`](JtsGeometries.java).
   - [`PropertyColumn`](PropertyColumn.java) and [`IdColumn`](IdColumn.java): values per key, with the column
     type picked once the layer is complete.
3. What both versions derive the same way:
   - [`GeometryStreams`](GeometryStreams.java): types, topology lengths and componentwise-delta vertices.
   - [`Triangles`](Triangles.java): earcut triangles of every polygon.
   - [`StringDictionary`](StringDictionary.java): distinct strings and codes.
   - [`ColumnGroup`](ColumnGroup.java): which string columns share a dictionary
     ([`SharedDictionaries`](SharedDictionaries.java)).
4. The writers, one per version, each self-contained so v1 and v2 can be read side by side:
   - [`V1LayerWriter`](V1LayerWriter.java), with [`V1IntegerStream`](V1IntegerStream.java),
     [`V1BooleanStream`](V1BooleanStream.java) and [`V1FastPfor`](V1FastPfor.java).
   - [`V2LayerWriter`](V2LayerWriter.java), with [`V2IntegerStream`](V2IntegerStream.java),
     [`V2Bits`](V2Bits.java) (presence encodings), [`V2Dictionary`](V2Dictionary.java) (plain / front coded /
     FSST dictionaries), [`V2FastPfor`](V2FastPfor.java) and [`TriangleTrees`](TriangleTrees.java).
5. Small utilities: [`ByteWriter`](ByteWriter.java), [`Bits`](Bits.java), [`IntList`](IntList.java),
   [`LongList`](LongList.java), [`Codecs`](Codecs.java) (lazily created FastPFOR / FSST).
6. FSST itself: mlt-core's
   [`SymbolTableBuilder`](../../../../../../../../mlt-core/src/main/java/org/maplibre/mlt/converter/encodings/fsst/SymbolTableBuilder.java),
   rewritten along with this encoder (see [FSST](#fsst) below).

## Results

Every distinct tile of a Massachusetts extract (15,877 tiles, 2.79M features, planetiler's OpenMapTiles
output), encoded with this encoder and with the native Rust encoder (`mlt-ffi`, built from this repo) using
the same options. Every tile was decoded with the Rust decoder and compared feature by feature: 0 tiles differ
in any configuration below. Rust always also tries the input order and, in v2, front coding.

| Options | This encoder | Rust | Ratio | Tiles/s, 1 thread: this / Rust |
|---|---:|---:|---:|---:|
| v1 | 241.30 MB | 241.27 MB | 1.000 | 10,087 / 3,545 (2.8x) |
| v1, FastPFOR + FSST | 225.95 MB | 225.87 MB | 1.000 | 5,953 / 2,222 (2.7x) |
| v1, FastPFOR + FSST + shared dictionaries | 181.16 MB | 181.16 MB | 1.000 | |
| v1, tessellated | 340.27 MB | 340.26 MB | 1.000 | |
| v1, Hilbert order tried | 240.24 MB | 240.21 MB | 1.000 | |
| v2 | 227.19 MB | 231.02 MB | 0.983 | 7,313 / 2,839 (2.6x) |
| v2, FastPFOR | 216.94 MB | 218.85 MB | 0.991 | |
| v2, FSST | 224.20 MB | 225.29 MB | 0.995 | |
| v2, FastPFOR + FSST | 213.96 MB | 213.19 MB | 1.004 | 4,440 / 1,052 (4.2x) |
| v2, FastPFOR + FSST + shared dictionaries | 172.90 MB | 172.97 MB | 1.000 | 5,619 / 1,348 (4.2x) |
| v2, ... + tessellated | 182.70 MB | 185.40 MB | 0.985 | 1,836 / 901 (2.0x) |
| v2, tessellated, triangles only | 233.86 MB | 261.06 MB | 0.896 | |
| v2, FastPFOR + FSST, Hilbert and id orders tried | 212.20 MB | 211.48 MB | 1.003 | |

M-series Mac, JDK 27. Rust is driven directly through its FFI, one call per feature, without re-parsing its
output. For reference, planetiler's MVT encoder does about 11,800 tiles/s on the same tiles.

v1 output is byte for byte what the previous version of this encoder wrote, before v2 and the other options
were added. v2 is also checked byte for byte against 85 of the reference fixtures in `test/synthetic/0x02`
(`V2FixtureTest`). Most of the others force an encoding or a type by name, or use features left out here (see
below).

### Where v2 sizes differ from Rust

Both encoders follow the same rules for what to store (for example, both drop polygon outlines with
"triangles only" only when every feature of the layer is a polygon). The differences come from how some
streams are written. This encoder does three things the Rust encoder doesn't, each of which Rust could adopt:

- **Tree symbols bit-packed at two bits per triangle**, as the spec describes. Rust writes a varint byte per
  symbol. This is most of the gap on tessellated layers (about 9% of tessellated v2).
- **Bit packing for every integer stream**, whenever it is the smallest encoding. Rust only bit-packs
  dictionary codes.
- **Empty streams stored without a byte length.**

One makes this encoder larger: with FastPFOR + FSST, Rust trains FSST twice per string column, on the plain and
on the front-coded dictionary, and keeps the better one. This encoder trains it once, on whichever is smaller
before compression, since FSST training is most of the time a string column takes. That is the 0.4% on v2
FastPFOR + FSST.

### FSST

FSST runs on each string column's dictionary, which in a tile is small: of the 51,476 Massachusetts
dictionaries of 256 bytes or more, 82% are under 1 KB. At that size the fixed cost of each call matters more
than the per-byte speed the C++ library is tuned for. mlt-core's
[`SymbolTableBuilder`](../../../../../../../../mlt-core/src/main/java/org/maplibre/mlt/converter/encodings/fsst/SymbolTableBuilder.java)
was rewritten from the reference implementation (cwida/fsst, MIT) for these inputs. On all those dictionaries
it takes 49 us each, against 348 us for mlt-core's previous Java port (about 7x) and 161 us for the C++ library
(about 3x), at the same compression (0.821 vs 0.818 for the previous port).

What made it fast, by measured impact:

- **Pair counting touches only the pairs it saw.** Pairs are recorded in a list the first time their counter
  goes up, and building the table and resetting the counters walk only that list instead of all 511 x 511
  counters every round (about 3x).
- **No allocation per call or per round.** The 1 MB pair counters, lookup tables and candidate arrays live in
  the instance (one per encoder) and are reset only where they were used (about 2.4x).
- **Symbols are primitive `long`s.** No symbol objects, `HashMap` or `PriorityQueue`: candidates go in a
  primitive open-addressing map and are ranked with one `long` sort, and matching is one 8-byte load and a
  mask compare against the reference's lookup tables, instead of comparing byte by byte (about 1.6x).
- **Training fitted to small inputs.** The whole input is the sample (up to 16 KB) and every round trains on
  all of it, where the reference grows the sample over the rounds. 5 rounds instead of 6, and when the sample
  is the whole input, the last round keeps exactly the symbols that save more than they cost.

## How it stays fast

- **No intermediate tile model.** MVT commands are read straight into flat int arrays, and columns hold their
  values in feature order, so a layer is a handful of arrays rather than an object per feature.
- **Exact sizes instead of trial encoding.** Each integer stream sizes every candidate encoding (none, delta,
  RLE, delta-RLE, bit-packed, fixed-width, and FastPFOR when enabled) in one pass and writes only the smallest.
  String columns do the same across plain, dictionary, front-coded and FSST layouts.
- **Reused buffers, lazy codecs.** Nothing is allocated per value, the output buffers grow to the largest tile
  once, and codecs that hold large tables are created on first use.
- **Deterministic.** Output depends only on the input and the options (FastPFOR exception padding, which
  JavaFastPFOR leaves stale between calls, is zeroed).

## Options

| Option | v1 | v2 |
|---|---|---|
| `fastPfor` | 256-value big-endian blocks (JavaFastPFOR) | 128-value little-endian blocks ([`V2FastPfor`](V2FastPfor.java)), 32-bit streams |
| `fsst` | FSST dictionary layout, dictionaries of 2 KB or more | same, plus over a front-coded dictionary |
| `sharedDictionaries` | `SharedDictionary` columns | shared dictionary columns, plain, front coded or FSST |
| `featureOrders` | input, id, Hilbert, Morton; several are tried and the smallest kept | same |
| `tessellation` | triangle counts + index buffer next to the rings | triangle trees, with the rings (`0xD`) or, for all-polygon layers with `TRIANGLES_ONLY`, without (`0xC`) |
| always | none / delta / RLE / delta-RLE varints, plain or dictionary strings | adds bit packing, fixed-width words, front-coded dictionaries, `Bitmap` / `Runs` / `Sparse` presence, shared presence fields, uniform geometry types |

Front coding (v2) sorts a dictionary's strings and stores each as the length of the prefix it shares with the
previous one plus the rest, so `Main Street`, `Main Street East`, `Maine Avenue` become `(0, "Main Street")`,
`(11, " East")`, `(4, "e Avenue")`.

## Left out, and why

- **Vertex dictionaries and Morton-coded vertices.** They only pay off when many vertices repeat exactly
  (polygons sharing borders, features stacked on one point). Rust tries them on every layer where fewer than
  66% of vertices are distinct, yet its v1 output on Massachusetts is within 0.04% of this encoder's, which
  never uses them. Tile simplification and merging leave few exact repeats, and componentwise deltas already
  store neighbouring vertices compactly. They would add about 150 lines, plus hashing every vertex of the layers
  that qualify. Morton codes mainly help spatial range queries, not size.
- **The 4-stream FSST layout** (FSST without a dictionary). Measured on Massachusetts with both layouts tried
  on every column: 11.8 KB smaller in total out of 237 MB (0.005%), for a second FSST training per column.
- **Delta2, ALP and float dictionaries, rANS vertices** (v2, experimental). Rust leaves ALP and float
  dictionaries off in every preset while they are still being measured, and enables Delta2 and rANS only in its
  minimum-size preset. Delta2 suits smooth sequences such as elevations and m-values, which 2D tiles don't
  have. Float columns are rare in vector tiles. rANS is the largest codec in the spec.
- **Nested properties, Z coordinates and m-values** (v2). Neither MVT commands nor 2D JTS geometries carry them.
  They need a richer input API, not just encoder changes.
- **`Int8` / `UInt8` / unsigned column types.** Int8 and UInt8 store the same 32-bit words as Int32, so
  they save nothing. Unsigned types would save the zigzag bit for non-negative columns. They were left out so
  that v1 output stays identical to the previous version, and could be added later.
- **64-bit FastPFOR** (v2). Only `LongId` and `Int64` streams have 64-bit words, and they are rare in tiles.

## Spec vs reference implementation

Found while writing v2 against `docs/specification/v2.md` and checking every tile with the Rust decoder (the
v2 spec makes the implementation authoritative where they differ):

- `docs/encodings.md` describes v2 FastPFOR blocks as interleaved for 4 SIMD lanes. The Rust decoder (the
  `fastpfor` crate's `FastPFor128`) reads them as 4 sequential groups of 32 values, as v1 does.
- A v2 shared dictionary child's codes stream is read as a string stream with the `Dict` layout in its
  extension bits (`0x09`), which the spec's grammar for shared dictionaries doesn't say.
- The v1 spec says the geometry types stream is `Data/None`, but both reference encoders tag it
  `Length/VarBinary`. This encoder does the same.

## Tests

`./gradlew :mlt-encoder:build` (206 tests): v1 tiles decoded with mlt-core's decoder for every geometry type and
pair of types, every property type, ids, FastPFOR / FSST (each suite rerun with them enabled), JTS input
(rewinding, mixed collections), sorting, order trials, shared dictionaries and tessellation. v2 checked byte for
byte against the reference fixtures, plus [`V2FastPfor`](V2FastPfor.java) round trips through JavaFastPFOR's
128-value decoder and [`TriangleTrees`](TriangleTrees.java) against the reference encoder's cases. There is no
Java v2 decoder yet, so the full v2 check (every Massachusetts tile, every option combination above) runs
outside this repo against the Rust decoder.
