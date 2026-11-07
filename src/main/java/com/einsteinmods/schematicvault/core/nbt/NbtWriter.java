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
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes {@link NbtTag}s as an NBT file (Java edition, big-endian), the way the game and the
 * editors save designs: gzip by default, the root an unnamed compound. A list must hold one tag
 * type (an empty one is written with the end type, as the game writes it); a list mixing types or a
 * string past modified UTF-8's 65,535 bytes fails with {@link NbtFormatException}.
 */
public final class NbtWriter {
  /** How the bytes are compressed. */
  public enum Compression {
    NONE,
    GZIP,
    ZLIB
  }

  private static final int BUFFER_SIZE = 64 * 1024;

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

  private NbtWriter() {}

  /** The file's bytes, gzip-compressed. */
  public static byte[] gzip(CompoundTag root) throws NbtFormatException {
    return write(root, Compression.GZIP);
  }

  public static byte[] write(CompoundTag root, Compression compression) throws NbtFormatException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(BUFFER_SIZE);
    try (OutputStream sink =
            switch (compression) {
              case NONE -> bytes;
              case GZIP -> new GZIPOutputStream(bytes, BUFFER_SIZE);
              case ZLIB -> new DeflaterOutputStream(bytes);
            };
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(sink))) {
      output.writeByte(TAG_COMPOUND);
      output.writeUTF("");
      payload(output, root);
    } catch (IOException exception) {
      // Memory streams fail only on what NBT cannot hold: a mixed list, an over-long string.
      throw new NbtFormatException("cannot write the file: " + exception.getMessage(), exception);
    }
    return bytes.toByteArray();
  }

  private static int typeOf(NbtTag tag) {
    return switch (tag) {
      case ByteTag _ -> TAG_BYTE;
      case ShortTag _ -> TAG_SHORT;
      case IntTag _ -> TAG_INT;
      case LongTag _ -> TAG_LONG;
      case FloatTag _ -> TAG_FLOAT;
      case DoubleTag _ -> TAG_DOUBLE;
      case ByteArrayTag _ -> TAG_BYTE_ARRAY;
      case StringTag _ -> TAG_STRING;
      case ListTag _ -> TAG_LIST;
      case CompoundTag _ -> TAG_COMPOUND;
      case IntArrayTag _ -> TAG_INT_ARRAY;
      case LongArrayTag _ -> TAG_LONG_ARRAY;
    };
  }

  private static void payload(DataOutputStream output, NbtTag tag) throws IOException {
    switch (tag) {
      case ByteTag(byte value) -> output.writeByte(value);
      case ShortTag(short value) -> output.writeShort(value);
      case IntTag(int value) -> output.writeInt(value);
      case LongTag(long value) -> output.writeLong(value);
      case FloatTag(float value) -> output.writeFloat(value);
      case DoubleTag(double value) -> output.writeDouble(value);
      case ByteArrayTag(byte[] value) -> {
        output.writeInt(value.length);
        output.write(value);
      }
      case StringTag(String value) -> output.writeUTF(value);
      case ListTag(List<NbtTag> values) -> list(output, values);
      case CompoundTag compound -> {
        for (Map.Entry<String, NbtTag> entry : compound.values().entrySet()) {
          output.writeByte(typeOf(entry.getValue()));
          output.writeUTF(entry.getKey());
          payload(output, entry.getValue());
        }
        output.writeByte(TAG_END);
      }
      case IntArrayTag(int[] value) -> {
        output.writeInt(value.length);
        for (int element : value) {
          output.writeInt(element);
        }
      }
      case LongArrayTag(long[] value) -> {
        output.writeInt(value.length);
        for (long element : value) {
          output.writeLong(element);
        }
      }
    }
  }

  private static void list(DataOutputStream output, List<NbtTag> values) throws IOException {
    int type = values.isEmpty() ? TAG_END : typeOf(values.getFirst());
    output.writeByte(type);
    output.writeInt(values.size());
    for (NbtTag value : values) {
      if (typeOf(value) != type) {
        throw new IOException("a list mixes tag types " + type + " and " + typeOf(value));
      }
      payload(output, value);
    }
  }
}
