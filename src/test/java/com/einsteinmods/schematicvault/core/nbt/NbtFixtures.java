package com.einsteinmods.schematicvault.core.nbt;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.StringTag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only NBT shorthands: fixture trees written the way the game and the editors save them. */
public final class NbtFixtures {
  private NbtFixtures() {}

  /** {@link NbtWriter#write}, failing the test on a tree NBT cannot hold. */
  public static byte[] write(CompoundTag root, NbtWriter.Compression compression) {
    try {
      return NbtWriter.write(root, compression);
    } catch (NbtFormatException exception) {
      throw new AssertionError(exception);
    }
  }

  public static byte[] gzip(CompoundTag root) {
    return write(root, NbtWriter.Compression.GZIP);
  }

  /** A compound from alternating keys and values. */
  public static CompoundTag compound(Object... keysAndValues) {
    Map<String, NbtTag> values = new LinkedHashMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      values.put((String) keysAndValues[index], (NbtTag) keysAndValues[index + 1]);
    }
    return new CompoundTag(values);
  }

  public static ListTag list(NbtTag... values) {
    return new ListTag(List.of(values));
  }

  public static IntTag integer(int value) {
    return new IntTag(value);
  }

  public static StringTag string(String value) {
    return new StringTag(value);
  }
}
