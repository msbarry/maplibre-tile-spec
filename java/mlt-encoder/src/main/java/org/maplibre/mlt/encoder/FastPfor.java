/*
 * Adapted from JavaFastPFOR (https://github.com/lemire/JavaFastPFOR), Apache License 2.0,
 * (c) Daniel Lemire: the block width choice and page layout of FastPFOR, VariableByte and their
 * Composition. Rewritten as an encoder only, which packs with one generic loop instead of
 * per-width unrolled methods, writes big-endian words straight to a ByteWriter, and can compute
 * the encoded size without encoding.
 */
package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * The v1 SIMD-FastPFOR physical encoding of 32-bit words: what JavaFastPFOR's {@code
 * Composition(FastPFOR, VariableByte)} produces, with every 32-bit word stored big-endian.
 *
 * <p>Values come in 256-value blocks, grouped in pages of up to 65536 values. Each block is
 * bit-packed at the width that minimizes its size, and the high bits of the few wider values
 * (exceptions) go to per-page arrays, one per extra width. The values after the last whole block
 * are variable-byte encoded. Not thread-safe: holds scratch arrays.
 */
final class FastPfor {

  /** Below this many values there is no whole block, so VarInt words are always smaller. */
  static final int MIN_VALUES = 256;

  private static final int BLOCK = 256;
  private static final int PAGE = 65536;
  private static final int BLOCKS_PER_PAGE = PAGE / BLOCK;
  private static final int EXCEPTION_BITS = 8;

  private final int[] widthCounts = new int[33];
  private final int[] blockWidth = new int[BLOCKS_PER_PAGE];
  private final int[] blockExceptions = new int[BLOCKS_PER_PAGE];
  private final int[] blockMaxWidth = new int[BLOCKS_PER_PAGE];
  private final int[] exceptionCounts = new int[33];
  private final int[][] exceptions = new int[33][];
  private final int[] packed = new int[32];
  private final ByteWriter bytes = new ByteWriter();

  /** Returns the byte length of the encoding of the first {@code count} words. */
  int size(int[] words, int count) {
    if (count == 0) {
      return 0;
    }
    int blockValues = count - count % BLOCK;
    long ints = 1;
    for (int pageStart = 0; pageStart < blockValues; pageStart += PAGE) {
      int blocks = Math.min(PAGE, blockValues - pageStart) / BLOCK;
      analyzePage(words, pageStart, blocks, false);
      ints += pageInts(blocks);
    }
    return (int) (4 * ints + tailBytes(words, blockValues, count));
  }

  /** Writes the encoding of the first {@code count} words, {@link #size} bytes. */
  void write(ByteWriter out, int[] words, int count) {
    if (count == 0) {
      return;
    }
    int blockValues = count - count % BLOCK;
    // With no whole block, FastPFOR writes nothing and Composition writes a 0 in its place.
    out.writeIntBE(blockValues);
    for (int pageStart = 0; pageStart < blockValues; pageStart += PAGE) {
      int blocks = Math.min(PAGE, blockValues - pageStart) / BLOCK;
      analyzePage(words, pageStart, blocks, true);
      writePage(out, words, pageStart, blocks);
    }
    writeTail(out, words, blockValues, count);
  }

  /**
   * Picks the width of each block of the page, as JavaFastPFOR does, and counts (and with {@code
   * collect}, gathers) the high bits of the exceptions by their extra width.
   */
  private void analyzePage(int[] words, int pageStart, int blocks, boolean collect) {
    Arrays.fill(exceptionCounts, 0);
    for (int block = 0; block < blocks; block++) {
      int start = pageStart + block * BLOCK;
      Arrays.fill(widthCounts, 0);
      for (int i = start; i < start + BLOCK; i++) {
        widthCounts[32 - Integer.numberOfLeadingZeros(words[i])]++;
      }
      int maxWidth = 32;
      while (widthCounts[maxWidth] == 0) {
        maxWidth--;
      }
      int bestWidth = maxWidth;
      int bestExceptions = 0;
      int bestCost = maxWidth * BLOCK;
      int exceptionsAbove = 0;
      for (int width = maxWidth - 1; width >= 0; width--) {
        exceptionsAbove += widthCounts[width + 1];
        if (exceptionsAbove == BLOCK) {
          break;
        }
        // Each exception costs its position byte and its extra bits, plus 8 bits for the block's
        // max width. A single extra bit is implied, so it costs nothing.
        int extraWidth = maxWidth - width;
        int cost =
            exceptionsAbove * EXCEPTION_BITS
                + exceptionsAbove * (extraWidth == 1 ? 0 : extraWidth)
                + width * BLOCK
                + 8;
        if (cost < bestCost) {
          bestCost = cost;
          bestWidth = width;
          bestExceptions = exceptionsAbove;
        }
      }
      blockWidth[block] = bestWidth;
      blockExceptions[block] = bestExceptions;
      blockMaxWidth[block] = maxWidth;
      int extraWidth = maxWidth - bestWidth;
      if (bestExceptions > 0 && extraWidth > 1) {
        if (collect) {
          int[] array = exceptionArray(extraWidth, exceptionCounts[extraWidth] + bestExceptions);
          for (int i = start; i < start + BLOCK; i++) {
            if ((words[i] >>> bestWidth) != 0) {
              array[exceptionCounts[extraWidth]++] = words[i] >>> bestWidth;
            }
          }
        } else {
          exceptionCounts[extraWidth] += bestExceptions;
        }
      }
    }
  }

  private int pageInts(int blocks) {
    int ints = 1 + 1 + (metadataBytes(blocks) + 3) / 4 + 1;
    for (int block = 0; block < blocks; block++) {
      ints += blockWidth[block] * (BLOCK / 32);
    }
    for (int width = 2; width <= 32; width++) {
      if (exceptionCounts[width] > 0) {
        ints += 1 + packedExceptionInts(exceptionCounts[width], width);
      }
    }
    return ints;
  }

  private int metadataBytes(int blocks) {
    int metadata = 0;
    for (int block = 0; block < blocks; block++) {
      metadata += blockExceptions[block] > 0 ? 3 + blockExceptions[block] : 2;
    }
    return metadata;
  }

  private void writePage(ByteWriter out, int[] words, int pageStart, int blocks) {
    int packedInts = 0;
    for (int block = 0; block < blocks; block++) {
      packedInts += blockWidth[block] * (BLOCK / 32);
    }
    out.writeIntBE(1 + packedInts);
    for (int block = 0; block < blocks; block++) {
      for (int group = pageStart + block * BLOCK;
          group < pageStart + (block + 1) * BLOCK;
          group += 32) {
        packGroup(out, words, group, blockWidth[block]);
      }
    }

    // Block metadata is a byte array that FastPFOR reads as little-endian ints.
    bytes.clear();
    for (int block = 0; block < blocks; block++) {
      bytes.writeByte(blockWidth[block]);
      bytes.writeByte(blockExceptions[block]);
      if (blockExceptions[block] > 0) {
        bytes.writeByte(blockMaxWidth[block]);
        int start = pageStart + block * BLOCK;
        for (int i = 0; i < BLOCK; i++) {
          if ((words[start + i] >>> blockWidth[block]) != 0) {
            bytes.writeByte(i);
          }
        }
      }
    }
    out.writeIntBE(bytes.size());
    writeLittleEndianInts(out);

    int bitmap = 0;
    for (int width = 2; width <= 32; width++) {
      if (exceptionCounts[width] > 0) {
        bitmap |= 1 << (width - 1);
      }
    }
    out.writeIntBE(bitmap);
    for (int width = 2; width <= 32; width++) {
      int count = exceptionCounts[width];
      if (count > 0) {
        out.writeIntBE(count);
        for (int group = 0; group < count; group += 32) {
          pack32(exceptions[width], group, width, Math.min(32, count - group));
          int ints = group + 32 <= count ? width : packedExceptionInts(count - group, width);
          writeInts(out, ints);
        }
      }
    }
  }

  /** The values after the last whole block, in VariableByte's format. */
  private void writeTail(ByteWriter out, int[] words, int from, int to) {
    bytes.clear();
    for (int i = from; i < to; i++) {
      long value = Integer.toUnsignedLong(words[i]);
      // Unlike VarInt, the high bit marks the last byte of each value.
      while (value >= 0x80) {
        bytes.writeByte((int) (value & 0x7F));
        value >>>= 7;
      }
      bytes.writeByte((int) value | 0x80);
    }
    writeLittleEndianInts(out);
  }

  private static int tailBytes(int[] words, int from, int to) {
    int tail = 0;
    for (int i = from; i < to; i++) {
      tail += ByteWriter.varintSize(Integer.toUnsignedLong(words[i]));
    }
    return (tail + 3) & ~3;
  }

  /** Writes {@link #bytes}, zero-padded to whole ints, as the big-endian ints they form in LE. */
  private void writeLittleEndianInts(ByteWriter out) {
    while ((bytes.size() & 3) != 0) {
      bytes.writeByte(0);
    }
    for (int i = 0; i < bytes.size(); i += 4) {
      out.writeIntBE(bytes.getIntLE(i));
    }
  }

  /**
   * Writes 32 values at {@code width} bits, LSB first, as {@code width} ints. Faster than {@link
   * #pack32} because it shifts into a 64-bit accumulator and writes each int once it is full.
   */
  private static void packGroup(ByteWriter out, int[] values, int from, int width) {
    if (width == 0) {
      return;
    }
    long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
    long accumulator = 0;
    int bits = 0;
    out.reserve(4 * width);
    for (int i = from; i < from + 32; i++) {
      accumulator |= (values[i] & mask) << bits;
      bits += width;
      if (bits >= 32) {
        out.writeIntBEUnchecked((int) accumulator);
        accumulator >>>= 32;
        bits -= 32;
      }
    }
  }

  /**
   * Packs {@code count} (up to 32) values at {@code width} bits, LSB first, into {@link #packed}.
   */
  private void pack32(int[] values, int from, int width, int count) {
    Arrays.fill(packed, 0, width, 0);
    long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
    int bit = 0;
    for (int i = 0; i < count; i++, bit += width) {
      long value = values[from + i] & mask;
      int word = bit >>> 5;
      int shift = bit & 31;
      packed[word] |= (int) (value << shift);
      if (shift + width > 32) {
        packed[word + 1] |= (int) (value >>> (32 - shift));
      }
    }
  }

  private void writeInts(ByteWriter out, int count) {
    for (int i = 0; i < count; i++) {
      out.writeIntBE(packed[i]);
    }
  }

  /** Ints taken by {@code count} values at {@code width} bits, as FastPFOR trims the last group. */
  private static int packedExceptionInts(int count, int width) {
    int padded = (count + 31) / 32 * 32;
    return padded / 32 * width - (padded - count) * width / 32;
  }

  private int[] exceptionArray(int width, int capacity) {
    if (exceptions[width] == null) {
      exceptions[width] = new int[Math.max(capacity, 1024)];
    } else if (exceptions[width].length < capacity) {
      exceptions[width] = Arrays.copyOf(exceptions[width], 2 * capacity);
    }
    return exceptions[width];
  }
}
