package org.maplibre.mlt.encoder;

import java.util.Arrays;
import java.util.List;
import org.maplibre.mlt.converter.encodings.fsst.SymbolTable;

/**
 * One way to store an MLT v2 string dictionary after its codes: a lengths stream, then the entry
 * bytes, optionally front coded and optionally FSST-compressed (v2 spec, "String Columns").
 *
 * <p>Front coding sorts the entries and stores each as the length of the prefix it shares with the
 * previous entry, plus the rest. Sorting renumbers the entries, so codes into a front-coded
 * dictionary go through {@link #recode}.
 */
final class V2Dictionary {

  private static final int BYTES_PLAIN = 0x04;
  private static final int BYTES_FRONT_CODED = 0x14;

  /** Entry lengths, or for a front-coded dictionary the prefix lengths then the suffix lengths. */
  private final LongList lengthValues;

  private final V2IntegerStream lengths;

  /** The entries, or for a front-coded dictionary the suffixes, back to back. */
  private final byte[] bytes;

  /** For a front-coded dictionary, the new code of each entry, otherwise null. */
  private final int[] newCode;

  /** The bytes compressed with FSST, or null. */
  private final SymbolTable fsst;

  private final V2IntegerStream symbolLengths;

  private V2Dictionary(
      LongList lengths, byte[] bytes, int[] newCode, SymbolTable fsst, Codecs codecs) {
    this.lengthValues = lengths;
    this.lengths = V2IntegerStream.of(lengths, false, false, codecs);
    this.bytes = bytes;
    this.newCode = newCode;
    this.fsst = fsst;
    this.symbolLengths = fsst == null ? null : symbolLengths(fsst, codecs);
  }

  /**
   * Returns the ways worth comparing: plain and front coded, plus FSST over whichever of the two is
   * smaller when FSST is enabled and the dictionary large enough. FSST runs only once, since
   * training it is what costs the most.
   */
  static List<V2Dictionary> candidates(StringDictionary dictionary, Codecs codecs) {
    var plain =
        new V2Dictionary(dictionary.entryLengths, concatenate(dictionary), null, null, codecs);
    var frontCoded = frontCoded(dictionary, codecs);
    if (!codecs.fsstEnabled() || dictionary.entryBytes < StringDictionary.MIN_FSST_BYTES) {
      return List.of(plain, frontCoded);
    }
    V2Dictionary base = frontCoded.size(-1) < plain.size(-1) ? frontCoded : plain;
    SymbolTable fsst = codecs.fsst().encode(base.bytes);
    var compressed = new V2Dictionary(base.lengthValues, base.bytes, base.newCode, fsst, codecs);
    return List.of(plain, frontCoded, compressed);
  }

  private static V2Dictionary frontCoded(StringDictionary dictionary, Codecs codecs) {
    List<byte[]> entries = dictionary.entries;
    Integer[] order = new Integer[entries.size()];
    Arrays.setAll(order, i -> i);
    Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(entries.get(a), entries.get(b)));
    int[] newCode = new int[entries.size()];
    var prefixLengths = new LongList();
    var suffixLengths = new LongList();
    var suffixes = new ByteWriter();
    byte[] previous = new byte[0];
    for (int i = 0; i < order.length; i++) {
      byte[] entry = entries.get(order[i]);
      newCode[order[i]] = i;
      // Entries are distinct, so they differ at some index or one is a prefix of the other.
      int prefix = Math.max(0, Arrays.mismatch(previous, entry));
      prefixLengths.add(prefix);
      suffixLengths.add(entry.length - prefix);
      suffixes.writeBytes(entry, prefix, entry.length - prefix);
      previous = entry;
    }
    for (int i = 0; i < suffixLengths.size(); i++) {
      prefixLengths.add(suffixLengths.get(i));
    }
    return new V2Dictionary(prefixLengths, suffixes.toByteArray(), newCode, null, codecs);
  }

  boolean isFsst() {
    return fsst != null;
  }

  /** Returns {@code codes} renumbered for this dictionary. */
  LongList recode(LongList codes) {
    if (newCode == null) {
      return codes;
    }
    var result = new LongList();
    for (int i = 0; i < codes.size(); i++) {
      result.add(newCode[(int) codes.get(i)]);
    }
    return result;
  }

  /** The bytes {@link #writeTo} writes, given the value count streams are implied to have. */
  long size(int impliedCount) {
    if (fsst == null) {
      return lengths.size(impliedCount) + blobSize(bytes.length);
    }
    return lengths.size(impliedCount)
        + symbolLengths.size(impliedCount)
        + blobSize(fsst.symbols().length)
        + blobSize(fsst.compressedData().length);
  }

  /**
   * Writes the lengths, then either the bytes, or the symbol lengths, symbol table and compressed
   * bytes. The last blob's encoding byte says whether the dictionary is front coded.
   */
  void writeTo(ByteWriter out, int impliedCount) {
    int blobEncoding = newCode != null ? BYTES_FRONT_CODED : BYTES_PLAIN;
    lengths.writeTo(out, impliedCount, 0);
    if (fsst == null) {
      writeBlob(out, blobEncoding, bytes);
    } else {
      symbolLengths.writeTo(out, impliedCount, 0);
      writeBlob(out, BYTES_PLAIN, fsst.symbols());
      writeBlob(out, blobEncoding, fsst.compressedData());
    }
  }

  /** A byte blob takes its value count from its byte length, so it never stores one. */
  private static void writeBlob(ByteWriter out, int encoding, byte[] blob) {
    out.writeByte(encoding);
    out.writeVarint(blob.length);
    out.writeBytes(blob);
  }

  static long blobSize(long byteLength) {
    return 1 + ByteWriter.varintSize(byteLength) + byteLength;
  }

  private static V2IntegerStream symbolLengths(SymbolTable fsst, Codecs codecs) {
    var lengths = new LongList();
    for (int length : fsst.symbolLengths()) {
      lengths.add(length);
    }
    return V2IntegerStream.of(lengths, false, false, codecs);
  }

  private static byte[] concatenate(StringDictionary dictionary) {
    byte[] result = new byte[(int) dictionary.entryBytes];
    int position = 0;
    for (byte[] utf8 : dictionary.entries) {
      System.arraycopy(utf8, 0, result, position, utf8.length);
      position += utf8.length;
    }
    return result;
  }
}
