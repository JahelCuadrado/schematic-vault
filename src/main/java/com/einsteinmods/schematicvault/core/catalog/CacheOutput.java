package com.einsteinmods.schematicvault.core.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The catalog cache's primitives, big-endian, into a growing buffer (the codec writes its string
 * table before the body, so the body is kept until then): fixed ints and longs, varints for counts
 * and string references, and UTF-8 strings with a varint length. Not thread-safe.
 */
final class CacheOutput {
  private static final int INITIAL_SIZE = 64 * 1024;
  private static final int VARINT_PAYLOAD = 0x7F;
  private static final int VARINT_MORE = 0x80;
  private static final int VARINT_SHIFT = 7;

  private byte[] bytes = new byte[INITIAL_SIZE];
  private int size;

  void writeByte(int value) {
    ensure(1);
    bytes[size++] = (byte) value;
  }

  void writeInt(int value) {
    ensure(Integer.BYTES);
    for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
      bytes[size++] = (byte) (value >>> shift);
    }
  }

  void writeLong(long value) {
    writeInt((int) (value >>> Integer.SIZE));
    writeInt((int) value);
  }

  /** A non-negative int in 1 to 5 bytes (counts, lengths, string references). */
  void writeVarInt(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("negative varint " + value);
    }
    writeVarLong(value);
  }

  /** Any long, small magnitudes in few bytes (zigzag: 0, -1, 1, -2... then a varint). */
  void writeSigned(long value) {
    writeVarLong((value << 1) ^ (value >> (Long.SIZE - 1)));
  }

  /** {@code value} read as unsigned, 7 bits per byte, low bits first. */
  private void writeVarLong(long value) {
    long rest = value;
    while ((rest & ~VARINT_PAYLOAD) != 0) {
      writeByte((int) (rest & VARINT_PAYLOAD) | VARINT_MORE);
      rest >>>= VARINT_SHIFT;
    }
    writeByte((int) rest);
  }

  /** {@code value} as UTF-8, failing when longer than {@code maxBytes} (the reader's cap). */
  void writeString(String value, int maxBytes) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > maxBytes) {
      throw new IOException("string of " + encoded.length + " bytes is too long for the cache");
    }
    writeVarInt(encoded.length);
    ensure(encoded.length);
    System.arraycopy(encoded, 0, bytes, size, encoded.length);
    size += encoded.length;
  }

  void writeTo(OutputStream output) throws IOException {
    output.write(bytes, 0, size);
  }

  private void ensure(int more) {
    if (size + more > bytes.length) {
      bytes = Arrays.copyOf(bytes, Math.max(size + more, bytes.length * 2));
    }
  }
}
