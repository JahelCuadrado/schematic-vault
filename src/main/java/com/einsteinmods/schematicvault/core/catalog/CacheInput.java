package com.einsteinmods.schematicvault.core.catalog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads {@link CacheOutput}'s primitives with its own buffer (no synchronized call per byte, unlike
 * {@code BufferedInputStream}). Every length and count is checked against a cap before anything is
 * allocated, so a corrupt file fails with {@link CatalogFormatException}, never a huge array; a cut
 * file ends in {@link EOFException}. Not thread-safe.
 */
final class CacheInput {
  private static final int BUFFER_SIZE = 64 * 1024;
  private static final int BYTE_MASK = 0xFF;
  private static final int VARINT_PAYLOAD = 0x7F;
  private static final int VARINT_MORE = 0x80;
  private static final int VARINT_SHIFT = 7;
  private static final int MAX_VARINT_SHIFT = Long.SIZE - 1;

  private final InputStream input;
  private final byte[] buffer = new byte[BUFFER_SIZE];
  private int position;
  private int limit;

  /** Grows to the longest string read; reused, so a string costs only its own copy. */
  private byte[] text = new byte[0];

  CacheInput(InputStream input) {
    this.input = input;
  }

  int readByte() throws IOException {
    if (position == limit) {
      fill();
    }
    return buffer[position++] & BYTE_MASK;
  }

  int readInt() throws IOException {
    int value = 0;
    for (int index = 0; index < Integer.BYTES; index++) {
      value = (value << Byte.SIZE) | readByte();
    }
    return value;
  }

  long readLong() throws IOException {
    return ((long) readInt() << Integer.SIZE) | Integer.toUnsignedLong(readInt());
  }

  /** A non-negative int written by {@link CacheOutput#writeVarInt}. */
  int readVarInt() throws IOException, CatalogFormatException {
    long value = readVarLong();
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new CatalogFormatException("bad number in the catalog cache");
    }
    return (int) value;
  }

  /** A long written by {@link CacheOutput#writeSigned}. */
  long readSigned() throws IOException, CatalogFormatException {
    long zigzag = readVarLong();
    return (zigzag >>> 1) ^ -(zigzag & 1);
  }

  /** An int written by {@link CacheOutput#writeSigned}; out of int range is a corrupt file. */
  int readSignedInt() throws IOException, CatalogFormatException {
    long value = readSigned();
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new CatalogFormatException("bad number in the catalog cache");
    }
    return (int) value;
  }

  private long readVarLong() throws IOException, CatalogFormatException {
    long value = 0;
    for (int shift = 0; shift <= MAX_VARINT_SHIFT; shift += VARINT_SHIFT) {
      int next = readByte();
      value |= (long) (next & VARINT_PAYLOAD) << shift;
      if ((next & VARINT_MORE) == 0) {
        return value;
      }
    }
    throw new CatalogFormatException("bad number in the catalog cache");
  }

  /** A count or length of at most {@code max}. */
  int readCount(int max) throws IOException, CatalogFormatException {
    int count = readVarInt();
    if (count > max) {
      throw new CatalogFormatException("count " + count + " over " + max + " in the catalog cache");
    }
    return count;
  }

  /** A UTF-8 string of at most {@code maxBytes} bytes. */
  String readString(int maxBytes) throws IOException, CatalogFormatException {
    int length = readCount(maxBytes);
    if (length > text.length) {
      text = new byte[Math.max(length, text.length * 2)];
    }
    int copied = 0;
    while (copied < length) {
      if (position == limit) {
        fill();
      }
      int chunk = Math.min(length - copied, limit - position);
      System.arraycopy(buffer, position, text, copied, chunk);
      position += chunk;
      copied += chunk;
    }
    return new String(text, 0, length, StandardCharsets.UTF_8);
  }

  private void fill() throws IOException {
    int read = input.read(buffer, 0, buffer.length);
    if (read <= 0) {
      throw new EOFException("catalog cache ends early");
    }
    position = 0;
    limit = read;
  }
}
