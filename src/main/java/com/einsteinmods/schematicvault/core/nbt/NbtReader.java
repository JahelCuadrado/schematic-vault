package com.einsteinmods.schematicvault.core.nbt;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.DoubleTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.FloatTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.StringTag;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a whole NBT file (Java edition, big-endian; gzip, zlib or uncompressed) into {@link
 * NbtTag}s. Validates before it allocates: past {@link #MAX_UNCOMPRESSED_BYTES} decompressed bytes,
 * past {@link #MAX_DEPTH} nested levels, an unknown tag type, a length longer than what is left of
 * the file or a truncated file fail with {@link NbtFormatException}, so a damaged or hostile file
 * costs no more than its own size.
 */
public final class NbtReader {
  /** No design file decompresses near this (the API's largest is about 60 MB). */
  public static final int MAX_UNCOMPRESSED_BYTES = 128 * 1024 * 1024;

  /** The game's own nesting limit. */
  public static final int MAX_DEPTH = 512;

  private static final int GZIP_MAGIC_FIRST = 0x1F;
  private static final int GZIP_MAGIC_SECOND = 0x8B;

  /** First byte of a zlib stream with the usual 32 KB window. */
  private static final int ZLIB_MAGIC = 0x78;

  private static final int BUFFER_SIZE = 64 * 1024;
  private static final int BYTE_MASK = 0xFF;
  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
  private static final long UNSIGNED_INT_MASK = 0xFFFFFFFFL;

  /** Modified UTF-8: lead bytes of one, two and three byte sequences, and continuation bytes. */
  private static final int ONE_BYTE_LIMIT = 0x80;

  private static final int TWO_BYTE_LEAD_MASK = 0xE0;
  private static final int TWO_BYTE_LEAD = 0xC0;
  private static final int TWO_BYTE_PAYLOAD = 0x1F;
  private static final int THREE_BYTE_LEAD_MASK = 0xF0;
  private static final int THREE_BYTE_LEAD = 0xE0;
  private static final int THREE_BYTE_PAYLOAD = 0x0F;
  private static final int CONTINUATION_MASK = 0xC0;
  private static final int CONTINUATION = 0x80;
  private static final int CONTINUATION_PAYLOAD = 0x3F;
  private static final int CONTINUATION_BITS = 6;

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_SHORT = 2;
  private static final int TAG_INT = 3;
  private static final int TAG_LONG = 4;
  private static final int TAG_FLOAT = 5;
  private static final int TAG_DOUBLE = 6;
  private static final int TAG_BYTE_ARRAY = 7;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;
  private static final int TAG_INT_ARRAY = 11;
  private static final int TAG_LONG_ARRAY = 12;

  private final byte[] data;
  private int position;

  private NbtReader(byte[] data) {
    this.data = data;
  }

  /** The root compound of a file's bytes, decompressing them first when they are compressed. */
  public static CompoundTag read(byte[] file) throws NbtFormatException {
    NbtReader reader = new NbtReader(decompressed(file));
    int type = reader.unsignedByte();
    if (type != TAG_COMPOUND) {
      throw new NbtFormatException("the root is not a compound (type " + type + ")");
    }
    reader.string();
    return reader.compound(1);
  }

  private static byte[] decompressed(byte[] file) throws NbtFormatException {
    if (file.length < 2) {
      throw new NbtFormatException("empty file");
    }
    int first = file[0] & BYTE_MASK;
    if (first == GZIP_MAGIC_FIRST && (file[1] & BYTE_MASK) == GZIP_MAGIC_SECOND) {
      return inflate(file, true);
    }
    return first == ZLIB_MAGIC ? inflate(file, false) : file;
  }

  private static byte[] inflate(byte[] file, boolean gzip) throws NbtFormatException {
    try (InputStream input =
        gzip
            ? new GZIPInputStream(new ByteArrayInputStream(file), BUFFER_SIZE)
            : new InflaterInputStream(new ByteArrayInputStream(file))) {
      ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(BUFFER_SIZE, file.length));
      byte[] buffer = new byte[BUFFER_SIZE];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > MAX_UNCOMPRESSED_BYTES) {
          throw new NbtFormatException("over " + MAX_UNCOMPRESSED_BYTES + " bytes decompressed");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new NbtFormatException("bad compressed data", exception);
    }
  }

  private NbtTag tag(int type, int depth) throws NbtFormatException {
    return switch (type) {
      case TAG_BYTE -> new ByteTag((byte) unsignedByte());
      case TAG_SHORT -> new ShortTag(shortValue());
      case TAG_INT -> new IntTag(intValue());
      case TAG_LONG -> new LongTag(longValue());
      case TAG_FLOAT -> new FloatTag(Float.intBitsToFloat(intValue()));
      case TAG_DOUBLE -> new DoubleTag(Double.longBitsToDouble(longValue()));
      case TAG_BYTE_ARRAY -> new ByteArrayTag(byteArray());
      case TAG_STRING -> new StringTag(string());
      case TAG_LIST -> list(depth + 1);
      case TAG_COMPOUND -> compound(depth + 1);
      case TAG_INT_ARRAY -> new IntArrayTag(intArray());
      case TAG_LONG_ARRAY -> new LongArrayTag(longArray());
      default -> throw new NbtFormatException("unknown tag type " + type + " at " + position);
    };
  }

  private CompoundTag compound(int depth) throws NbtFormatException {
    checkDepth(depth);
    Map<String, NbtTag> values = new LinkedHashMap<>();
    while (true) {
      int type = unsignedByte();
      if (type == TAG_END) {
        return new CompoundTag(values);
      }
      String name = string();
      values.put(name, tag(type, depth));
    }
  }

  private ListTag list(int depth) throws NbtFormatException {
    checkDepth(depth);
    int type = unsignedByte();
    int count = intValue();
    // Every element takes at least one byte, except in an (empty) list of TAG_END.
    if (count < 0 || count > remaining() || type == TAG_END && count != 0) {
      throw new NbtFormatException("bad list length " + count + " at " + position);
    }
    List<NbtTag> values = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      values.add(tag(type, depth));
    }
    return new ListTag(values);
  }

  private static void checkDepth(int depth) throws NbtFormatException {
    if (depth > MAX_DEPTH) {
      throw new NbtFormatException("nested deeper than " + MAX_DEPTH);
    }
  }

  private int remaining() {
    return data.length - position;
  }

  private void need(long bytes) throws NbtFormatException {
    if (bytes < 0 || bytes > remaining()) {
      throw new NbtFormatException("truncated at " + position);
    }
  }

  private int unsignedByte() throws NbtFormatException {
    need(Byte.BYTES);
    return data[position++] & BYTE_MASK;
  }

  private short shortValue() throws NbtFormatException {
    need(Short.BYTES);
    int value = (data[position] & BYTE_MASK) << Byte.SIZE | data[position + 1] & BYTE_MASK;
    position += Short.BYTES;
    return (short) value;
  }

  private int intValue() throws NbtFormatException {
    need(Integer.BYTES);
    int value = intAt(position);
    position += Integer.BYTES;
    return value;
  }

  private int intAt(int at) {
    return (data[at] & BYTE_MASK) << Byte.SIZE * 3
        | (data[at + 1] & BYTE_MASK) << Byte.SIZE * 2
        | (data[at + 2] & BYTE_MASK) << Byte.SIZE
        | data[at + 3] & BYTE_MASK;
  }

  private long longValue() throws NbtFormatException {
    need(Long.BYTES);
    long value = longAt(position);
    position += Long.BYTES;
    return value;
  }

  private long longAt(int at) {
    return (long) intAt(at) << Integer.SIZE | intAt(at + Integer.BYTES) & UNSIGNED_INT_MASK;
  }

  private byte[] byteArray() throws NbtFormatException {
    int length = intValue();
    need(length);
    byte[] value = new byte[length];
    System.arraycopy(data, position, value, 0, length);
    position += length;
    return value;
  }

  private int[] intArray() throws NbtFormatException {
    int length = intValue();
    need((long) length * Integer.BYTES);
    int[] value = new int[length];
    for (int index = 0; index < length; index++) {
      value[index] = intAt(position);
      position += Integer.BYTES;
    }
    return value;
  }

  private long[] longArray() throws NbtFormatException {
    int length = intValue();
    need((long) length * Long.BYTES);
    long[] value = new long[length];
    for (int index = 0; index < length; index++) {
      value[index] = longAt(position);
      position += Long.BYTES;
    }
    return value;
  }

  /** A length-prefixed string in Java's modified UTF-8 (as {@code DataInput#readUTF}). */
  private String string() throws NbtFormatException {
    int length = shortValue() & UNSIGNED_SHORT_MASK;
    need(length);
    int end = position + length;
    StringBuilder text = new StringBuilder(length);
    while (position < end) {
      int first = data[position++] & BYTE_MASK;
      if (first < ONE_BYTE_LIMIT) {
        text.append((char) first);
      } else if ((first & TWO_BYTE_LEAD_MASK) == TWO_BYTE_LEAD) {
        int second = continuation(end);
        text.append((char) ((first & TWO_BYTE_PAYLOAD) << CONTINUATION_BITS | second));
      } else if ((first & THREE_BYTE_LEAD_MASK) == THREE_BYTE_LEAD) {
        int second = continuation(end);
        int third = continuation(end);
        text.append(
            (char)
                ((first & THREE_BYTE_PAYLOAD) << CONTINUATION_BITS * 2
                    | second << CONTINUATION_BITS
                    | third));
      } else {
        throw new NbtFormatException("bad UTF-8 at " + (position - 1));
      }
    }
    return text.toString();
  }

  private int continuation(int end) throws NbtFormatException {
    if (position >= end || (data[position] & CONTINUATION_MASK) != CONTINUATION) {
      throw new NbtFormatException("bad UTF-8 at " + position);
    }
    return data[position++] & CONTINUATION_PAYLOAD;
  }
}
