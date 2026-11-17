/*
 * Adapted from the FSST reference implementation, https://github.com/cwida/fsst (MIT License,
 * Copyright (c) 2018-2020, CWI, TU Munich, FSU Jena), described in Boncz, Neumann and Leis, "FSST:
 * Fast Random Access String Compression" (VLDB 2020): the training (5 rounds counting symbols and
 * symbol pairs, gains with single-byte symbols promoted 8 times, keeping the table of the best
 * round), and the lookup of the longest symbol (a lossy hash table for symbols of 3 or more bytes,
 * plus tables indexed by the first one or two bytes). Rewritten for Java with symbols as primitive
 * longs, counters and lookup tables reused across calls and reset only where they were used, and one
 * buffer compressed as a whole, as MLT stores it. Changed for the small inputs of a tile: the
 * sample is the whole input up to 16 KB, every round trains on the whole sample (the reference
 * grows the sample over the rounds) with a fixed minimum count, and when the sample is the whole
 * input the last round keeps exactly the symbols that save more than they cost. The terminator byte and the code
 * renumbering for decoding speed are left out.
 */
package org.maplibre.mlt.converter.encodings.fsst;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * FSST compression: trains a table of up to 255 symbols of 1 to 8 bytes on a buffer, then replaces
 * the buffer's bytes with one-byte codes for those symbols, escaping bytes that no symbol covers.
 *
 * <p>An instance holds about 1.5 MB of tables that it reuses for each call, so reuse one per thread
 * to compress many buffers. Instances are not thread-safe.
 */
public final class SymbolTableBuilder {

  /** Bytes past the end of the input that the symbol lookup reads. */
  private static final int PADDING = 8;

  // Reads the 8 bytes at any offset of a byte array as one long in a single load. Little-endian
  // puts the first byte in the low bits, so masking with (1L << 8 * length) - 1 keeps exactly the
  // first length bytes, which matches a symbol of that length in one compare.
  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private static final int MAX_SYMBOLS = 255;
  private static final int ESCAPE = 255;

  /** Codes below this are single bytes without a symbol, which compress to an escape. */
  private static final int CODE_BASE = 256;

  private static final int CODES = CODE_BASE + MAX_SYMBOLS;
  private static final int HASH_SIZE = 1024;
  private static final int ROUNDS = 5;

  /**
   * Candidates seen fewer times are not worth a symbol, and leaving them out speeds training up.
   */
  private static final int MIN_COUNT = 5;

  private static final int SAMPLE_BYTES = 16 * 1024;
  private static final int SAMPLE_CHUNK = 512;
  private static final long HASH_PRIME = 2971215073L;
  private static final long[] MASKS = new long[9];

  static {
    for (int length = 1; length < 8; length++) {
      MASKS[length] = (1L << (8 * length)) - 1;
    }
    MASKS[8] = -1L;
  }

  // The symbol of each code: bytes little-endian in a long, and their count.
  private final long[] symbolValues = new long[CODES];
  private final int[] symbolLengths = new int[CODES];
  private int symbolCount;

  // Lookup of the longest symbol at a position. Unused entries hold -1, except byteCodes, which
  // holds the byte itself (an escape) for bytes without a single-byte symbol.
  private final int[] byteCodes = new int[256];
  private final int[] shortCodes = new int[1 << 16];
  private final int[] hashCodes = new int[HASH_SIZE];

  // Training counters: of each code, and of each pair of codes that follow each other.
  private final int[] counts = new int[CODES];
  private final int[] pairCounts = new int[CODES * CODES];
  private int[] countedPairs = new int[1024];
  private int countedPairCount;

  // Candidate symbols of the next table, deduplicated in an open-addressing table.
  private long[] candidateValues = new long[1024];
  private int[] candidateLengths = new int[1024];
  private long[] candidateGains = new long[1024];
  private int[] candidateSlots = new int[512];
  private int candidateCount;
  private long[] order = new long[512];

  // The table of the best round and its counts.
  private final long[] bestValues = new long[CODES];
  private final int[] bestLengths = new int[CODES];
  private final int[] bestCounts = new int[CODES];
  private int bestSymbolCount;

  /** Start and end of each line of the training sample. */
  private final int[] sampleLines = new int[2 * SAMPLE_BYTES / SAMPLE_CHUNK];

  private int sampleLineCount;

  /** Whether the sample is the whole text, so the last round counts every symbol use. */
  private boolean sampledAll;

  /** The input, followed by {@link #PADDING} bytes. */
  private byte[] text = new byte[1024];

  private byte[] compressed = new byte[1024];

  /** Creates a builder, allocating its tables. */
  public SymbolTableBuilder() {
    for (int b = 0; b < CODE_BASE; b++) {
      symbolValues[b] = b;
      symbolLengths[b] = 1;
      byteCodes[b] = b;
    }
    Arrays.fill(shortCodes, -1);
    Arrays.fill(hashCodes, -1);
  }

  /** Trains a symbol table on {@code data} and compresses {@code data} with it as one buffer. */
  public SymbolTable encode(byte[] data) {
    int length = data.length;
    if (text.length < length + PADDING) {
      text = new byte[Math.max(length + PADDING, 2 * text.length)];
    }
    System.arraycopy(data, 0, text, 0, length);
    chooseSample(length);
    train(text);

    int[] trainedLengths = Arrays.copyOfRange(symbolLengths, CODE_BASE, CODE_BASE + symbolCount);
    byte[] symbols = new byte[Arrays.stream(trainedLengths).sum()];
    int symbolBytes = 0;
    for (int code = CODE_BASE; code < CODE_BASE + symbolCount; code++) {
      for (int i = 0; i < symbolLengths[code]; i++) {
        symbols[symbolBytes++] = (byte) (symbolValues[code] >>> (8 * i));
      }
    }

    // Each input byte compresses to at most an escape and the byte.
    if (compressed.length < 2 * length) {
      compressed = new byte[Math.max(2 * length, 2 * compressed.length)];
    }
    int compressedLength = 0;
    int position = 0;
    while (position < length) {
      int code = longestSymbol(text, position, length);
      if (code < CODE_BASE) {
        compressed[compressedLength++] = (byte) ESCAPE;
        compressed[compressedLength++] = (byte) code;
        position++;
      } else {
        compressed[compressedLength++] = (byte) (code - CODE_BASE);
        position += symbolLengths[code];
      }
    }
    return new SymbolTable(
        symbols, trainedLengths, Arrays.copyOf(compressed, compressedLength), length);
  }

  private void train(byte[] text) {
    clearTable();
    clearCounts();
    long bestGain = Long.MIN_VALUE;
    for (int round = 1; ; round++) {
      boolean lastRound = round == ROUNDS;
      long gain = countSymbols(text, !lastRound);
      if (gain >= bestGain) {
        bestGain = gain;
        saveBest();
      }
      if (lastRound) {
        break;
      }
      makeTable(false);
      clearCounts();
    }
    clearCounts();
    restoreBest();
    makeTable(true);
  }

  /**
   * Picks the lines to train on: the whole text when it is at most 16 KB, otherwise 16 KB of
   * 512-byte chunks evenly spread over it, as the reference does.
   */
  private void chooseSample(int length) {
    sampleLineCount = 0;
    sampledAll = length <= SAMPLE_BYTES;
    if (sampledAll) {
      if (length > 0) {
        addSampleLine(0, length);
      }
    } else {
      int chunks = SAMPLE_BYTES / SAMPLE_CHUNK;
      for (int i = 0; i < chunks; i++) {
        int start = (int) ((long) i * (length - SAMPLE_CHUNK) / (chunks - 1));
        addSampleLine(start, start + SAMPLE_CHUNK);
      }
    }
  }

  private void addSampleLine(int start, int end) {
    sampleLines[2 * sampleLineCount] = start;
    sampleLines[2 * sampleLineCount + 1] = end;
    sampleLineCount++;
  }

  /**
   * Compresses the sample lines with the current table, counting each code and (with {@code
   * countPairs}) each pair of consecutive codes, and returns the bytes saved.
   */
  private long countSymbols(byte[] text, boolean countPairs) {
    long gain = 0;
    for (int line = 0; line < sampleLineCount; line++) {
      int start1 = sampleLines[2 * line];
      int end = sampleLines[2 * line + 1];
      int code1 = longestSymbol(text, start1, end);
      int position = start1 + symbolLengths[code1];
      gain += symbolLengths[code1] - (code1 < CODE_BASE ? 2 : 1);
      while (true) {
        counts[code1]++;
        // Also count the first byte alone, as a shorter alternative to the symbol.
        if (symbolLengths[code1] != 1) {
          counts[text[start1] & 0xFF]++;
        }
        if (position == end) {
          break;
        }
        int start2 = position;
        int code2 = longestSymbol(text, position, end);
        position += symbolLengths[code2];
        gain += symbolLengths[code2] - (code2 < CODE_BASE ? 2 : 1);
        if (countPairs) {
          countPair(code1, code2);
          if (symbolLengths[code2] != 1) {
            countPair(code1, text[start2] & 0xFF);
          }
        }
        code1 = code2;
        start1 = start2;
      }
    }
    return gain;
  }

  private void countPair(int code1, int code2) {
    int index = code1 * CODES + code2;
    if (pairCounts[index]++ == 0) {
      if (countedPairCount == countedPairs.length) {
        countedPairs = Arrays.copyOf(countedPairs, countedPairCount * 2);
      }
      countedPairs[countedPairCount++] = index;
    }
  }

  /**
   * Replaces the table with the 255 candidates of highest gain: each counted symbol, and unless
   * {@code lastRound}, each concatenation of a counted pair.
   */
  private void makeTable(boolean lastRound) {
    clearCandidates(CODE_BASE + symbolCount + (lastRound ? 0 : countedPairCount));
    for (int code = 0; code < CODE_BASE + symbolCount; code++) {
      int length = symbolLengths[code];
      if (counts[code] > 0) {
        if (lastRound && sampledAll) {
          // The counts are exact, so keep exactly the symbols that save more than they cost: each
          // use saves its bytes but one (or an escape byte for a single byte), and storing one
          // costs its bytes plus its length.
          long saved = (long) counts[code] * (length == 1 ? 1 : length - 1);
          if (saved <= length + 1) {
            continue;
          }
        }
        // Single-byte symbols are promoted, as the reference does, to reduce escapes.
        long count = (length == 1 ? 8L : 1L) * counts[code];
        addCandidate(symbolValues[code], length, count, lastRound && sampledAll ? 0 : MIN_COUNT);
      }
    }
    if (!lastRound) {
      for (int i = 0; i < countedPairCount; i++) {
        int code1 = countedPairs[i] / CODES;
        int code2 = countedPairs[i] % CODES;
        int length1 = symbolLengths[code1];
        if (length1 < 8) {
          int length = Math.min(8, length1 + symbolLengths[code2]);
          long value =
              (symbolValues[code1] | (symbolValues[code2] << (8 * length1))) & MASKS[length];
          addCandidate(value, length, pairCounts[countedPairs[i]], MIN_COUNT);
        }
      }
    }

    // Highest gain first, ties in the order the candidates were found.
    if (order.length < candidateCount) {
      order = new long[candidateSlots.length];
    }
    for (int i = 0; i < candidateCount; i++) {
      order[i] = (candidateGains[candidateSlots[i]] << 24) | (0xFFFFFF - i);
    }
    Arrays.sort(order, 0, candidateCount);
    clearTable();
    for (int i = candidateCount - 1; i >= 0 && symbolCount < MAX_SYMBOLS; i--) {
      int slot = candidateSlots[0xFFFFFF - (int) (order[i] & 0xFFFFFF)];
      addSymbol(candidateValues[slot], candidateLengths[slot]);
    }
  }

  private void addCandidate(long value, int length, long count, int minCount) {
    if (count < minCount) {
      return;
    }
    int mask = candidateLengths.length - 1;
    int slot = (int) hash(value * 9 + length) & mask;
    while (candidateLengths[slot] != 0
        && (candidateValues[slot] != value || candidateLengths[slot] != length)) {
      slot = (slot + 1) & mask;
    }
    if (candidateLengths[slot] == 0) {
      candidateValues[slot] = value;
      candidateLengths[slot] = length;
      candidateGains[slot] = 0;
      candidateSlots[candidateCount++] = slot;
    }
    candidateGains[slot] += count * length;
  }

  private void clearCandidates(int maxCandidates) {
    for (int i = 0; i < candidateCount; i++) {
      candidateLengths[candidateSlots[i]] = 0;
    }
    candidateCount = 0;
    if (candidateLengths.length < 2 * maxCandidates) {
      int capacity = Integer.highestOneBit(4 * maxCandidates - 1);
      candidateValues = new long[capacity];
      candidateLengths = new int[capacity];
      candidateGains = new long[capacity];
      candidateSlots = new int[capacity];
    }
  }

  /** Adds a symbol unless it is 3 bytes or more and its hash slot is taken. */
  private void addSymbol(long value, int length) {
    int code = CODE_BASE + symbolCount;
    if (length == 1) {
      byteCodes[(int) value] = code;
    } else if (length == 2) {
      shortCodes[(int) value] = code;
    } else {
      int slot = hashSlot(value);
      if (hashCodes[slot] >= 0) {
        return;
      }
      hashCodes[slot] = code;
    }
    symbolValues[code] = value;
    symbolLengths[code] = length;
    symbolCount++;
  }

  /** Returns the code of the longest symbol at {@code position}, or its byte if there is none. */
  private int longestSymbol(byte[] text, int position, int end) {
    long word = (long) LONG_LE.get(text, position);
    int remaining = end - position;
    int code = hashCodes[hashSlot(word)];
    if (code >= 0) {
      int length = symbolLengths[code];
      if (length <= remaining && (word & MASKS[length]) == symbolValues[code]) {
        return code;
      }
    }
    if (remaining >= 2) {
      code = shortCodes[(int) word & 0xFFFF];
      if (code >= 0) {
        return code;
      }
    }
    return byteCodes[(int) word & 0xFF];
  }

  private static int hashSlot(long word) {
    return (int) hash(word & 0xFFFFFF) & (HASH_SIZE - 1);
  }

  private static long hash(long value) {
    long product = value * HASH_PRIME;
    return product ^ (product >>> 15);
  }

  private void clearTable() {
    for (int code = CODE_BASE; code < CODE_BASE + symbolCount; code++) {
      long value = symbolValues[code];
      switch (symbolLengths[code]) {
        case 1 -> byteCodes[(int) value] = (int) value;
        case 2 -> shortCodes[(int) value] = -1;
        default -> hashCodes[hashSlot(value)] = -1;
      }
    }
    symbolCount = 0;
  }

  private void clearCounts() {
    Arrays.fill(counts, 0);
    for (int i = 0; i < countedPairCount; i++) {
      pairCounts[countedPairs[i]] = 0;
    }
    countedPairCount = 0;
  }

  private void saveBest() {
    bestSymbolCount = symbolCount;
    System.arraycopy(symbolValues, CODE_BASE, bestValues, CODE_BASE, symbolCount);
    System.arraycopy(symbolLengths, CODE_BASE, bestLengths, CODE_BASE, symbolCount);
    System.arraycopy(counts, 0, bestCounts, 0, CODES);
  }

  private void restoreBest() {
    clearTable();
    for (int code = CODE_BASE; code < CODE_BASE + bestSymbolCount; code++) {
      addSymbol(bestValues[code], bestLengths[code]);
    }
    System.arraycopy(bestCounts, 0, counts, 0, CODES);
  }
}
