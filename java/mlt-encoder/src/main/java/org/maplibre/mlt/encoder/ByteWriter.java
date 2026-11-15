package org.maplibre.mlt.encoder;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/** A growable byte buffer with the primitives MLT is written with, reused across tiles. */
final class ByteWriter {

  private static final VarHandle INT_LE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle INT_BE =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private byte[] bytes = new byte[256];
  private int size;

  /** Returns how many bytes the unsigned varint of {@code value} takes. */
  static int varintSize(long value) {
    int bits = 64 - Long.numberOfLeadingZeros(value | 1);
    return (bits + 6) / 7;
  }

  int size() {
    return size;
  }

  void clear() {
    size = 0;
  }

  void truncate(int newSize) {
    size = newSize;
  }

  void writeByte(int value) {
    ensureCapacity(1);
    bytes[size++] = (byte) value;
  }

  /** Writes {@code value} as an unsigned varint. */
  void writeVarint(long value) {
    ensureCapacity(10);
    while ((value & ~0x7FL) != 0) {
      bytes[size++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    bytes[size++] = (byte) value;
  }

  void writeIntLE(int value) {
    ensureCapacity(4);
    INT_LE.set(bytes, size, value);
    size += 4;
  }

  void writeIntBE(int value) {
    ensureCapacity(4);
    INT_BE.set(bytes, size, value);
    size += 4;
  }

  int getIntLE(int offset) {
    return (int) INT_LE.get(bytes, offset);
  }

  void writeLongLE(long value) {
    ensureCapacity(8);
    LONG_LE.set(bytes, size, value);
    size += 8;
  }

  void writeBytes(byte[] values) {
    writeBytes(values, 0, values.length);
  }

  void writeBytes(byte[] values, int offset, int length) {
    ensureCapacity(length);
    System.arraycopy(values, offset, bytes, size, length);
    size += length;
  }

  void writeBytes(ByteWriter other) {
    writeBytes(other.bytes, 0, other.size);
  }

  /** Writes a string as its UTF-8 byte length then its bytes. */
  void writeString(byte[] utf8) {
    writeVarint(utf8.length);
    writeBytes(utf8);
  }

  byte[] toByteArray() {
    return Arrays.copyOf(bytes, size);
  }

  private void ensureCapacity(int extra) {
    if (size + extra > bytes.length) {
      bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
    }
  }
}
