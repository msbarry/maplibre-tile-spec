package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * The MLT v2 FastPFOR physical encoding of 32-bit words (docs/encodings.md, "FastPFOR"): 128-value
 * blocks, each packed at the width most of its values need, with the high bits of the few wider
 * values in per-page exception arrays, all in little-endian words. Not thread-safe: it reuses
 * scratch buffers.
 *
 * <p>Blocks are packed as 4 sequential groups of 32 values, as the reference decoder (the Rust
 * {@code fastpfor} crate's {@code FastPFor128}) reads them, although docs/encodings.md describes an
 * interleaved layout. Where the two disagree, the v2 spec makes the implementation authoritative.
 */
final class V2FastPfor {

  /** Below this many values there is no whole block, so VarInt words are always smaller. */
  static final int MIN_VALUES = 128;

  private static final int BLOCK = 128;
  private static final int PAGE = 65536;

  private final IntList out = new IntList();
  private final ByteWriter blockMetadata = new ByteWriter();
  private final ByteWriter tail = new ByteWriter();
  private final IntList[] exceptions = new IntList[33];
  private final int[] widthCounts = new int[33];

  V2FastPfor() {
    for (int k = 0; k < exceptions.length; k++) {
      exceptions[k] = new IntList();
    }
  }

  /** Returns the encoding of the first {@code count} words, as 32-bit words. */
  IntList encode(int[] words, int count) {
    out.clear();
    if (count == 0) {
      return out;
    }
    int blockValues = count - count % BLOCK;
    out.add(blockValues);
    for (int pageStart = 0; pageStart < blockValues; pageStart += PAGE) {
      writePage(words, pageStart, Math.min(blockValues, pageStart + PAGE));
    }
    writeTail(words, blockValues, count);
    return out;
  }

  private void writePage(int[] words, int start, int end) {
    int metaOffsetIndex = out.size();
    out.add(0);
    blockMetadata.clear();
    for (IntList list : exceptions) {
      list.clear();
    }
    for (int block = start; block < end; block += BLOCK) {
      writeBlock(words, block);
    }
    out.array()[metaOffsetIndex] = out.size() - metaOffsetIndex;
    out.add(blockMetadata.size());
    addPaddedToWords(blockMetadata);
    int bitmap = 0;
    for (int k = 2; k <= 32; k++) {
      if (exceptions[k].size() > 0) {
        bitmap |= 1 << (k - 1);
      }
    }
    out.add(bitmap);
    for (int k = 2; k <= 32; k++) {
      IntList values = exceptions[k];
      if (values.size() > 0) {
        out.add(values.size());
        packSequential(values.array(), 0, values.size(), k);
      }
    }
  }

  /** Packs one block at the cheapest width, and records its exceptions and metadata. */
  private void writeBlock(int[] words, int start) {
    Arrays.fill(widthCounts, 0);
    for (int i = start; i < start + BLOCK; i++) {
      widthCounts[bitWidth(words[i])]++;
    }
    int maxWidth = 32;
    while (maxWidth > 0 && widthCounts[maxWidth] == 0) {
      maxWidth--;
    }
    // The cost in bits of each width, as the reference encoder computes it: the packed block, then
    // per exception its position byte and high bits (none when only one bit wider), plus the
    // max_width byte.
    int width = maxWidth;
    long bestCost = (long) BLOCK * maxWidth;
    int wider = 0;
    for (int candidate = maxWidth - 1; candidate >= 0; candidate--) {
      wider += widthCounts[candidate + 1];
      if (wider == BLOCK) {
        break;
      }
      int highBits = maxWidth - candidate == 1 ? 0 : maxWidth - candidate;
      long cost = (long) BLOCK * candidate + wider * 8L + (long) wider * highBits + 8;
      if (cost < bestCost) {
        bestCost = cost;
        width = candidate;
      }
    }
    int exceptionCount = 0;
    for (int i = start; i < start + BLOCK; i++) {
      exceptionCount += bitWidth(words[i]) > width ? 1 : 0;
    }
    blockMetadata.writeByte(width);
    blockMetadata.writeByte(exceptionCount);
    if (exceptionCount > 0) {
      blockMetadata.writeByte(maxWidth);
      // An exception only one bit wider needs no array: its high part can only be 1.
      IntList highBits = maxWidth - width > 1 ? exceptions[maxWidth - width] : null;
      for (int i = start; i < start + BLOCK; i++) {
        if (bitWidth(words[i]) > width) {
          blockMetadata.writeByte(i - start);
          if (highBits != null) {
            highBits.add(words[i] >>> width);
          }
        }
      }
    }
    packSequential(words, start, start + BLOCK, width);
  }

  /**
   * Packs values as one LSB-first bit stream, without the words only padding would fill. A group of
   * 32 values at {@code width} bits fills exactly {@code width} words, so this is also the layout
   * of consecutive 32-value groups.
   */
  private void packSequential(int[] values, int start, int end, int width) {
    long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
    long buffer = 0;
    int bufferBits = 0;
    for (int i = start; i < end; i++) {
      buffer |= (values[i] & mask) << bufferBits;
      bufferBits += width;
      if (bufferBits >= 32) {
        out.add((int) buffer);
        buffer >>>= 32;
        bufferBits -= 32;
      }
    }
    if (bufferBits > 0) {
      out.add((int) buffer);
    }
  }

  /**
   * Writes the values after the last whole block as variable bytes: 7 bits per byte, least
   * significant first, with bit 7 set on the last byte of each value, padded to whole words.
   */
  private void writeTail(int[] words, int start, int end) {
    tail.clear();
    for (int i = start; i < end; i++) {
      int value = words[i];
      while (Integer.compareUnsigned(value, 0x80) >= 0) {
        tail.writeByte(value & 0x7F);
        value >>>= 7;
      }
      tail.writeByte(value | 0x80);
    }
    addPaddedToWords(tail);
  }

  private void addPaddedToWords(ByteWriter bytes) {
    while (bytes.size() % 4 != 0) {
      bytes.writeByte(0);
    }
    for (int i = 0; i < bytes.size(); i += 4) {
      out.add(bytes.getIntLE(i));
    }
  }

  private static int bitWidth(int value) {
    return 32 - Integer.numberOfLeadingZeros(value);
  }
}
