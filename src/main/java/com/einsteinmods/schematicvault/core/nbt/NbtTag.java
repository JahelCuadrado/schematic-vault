package com.einsteinmods.schematicvault.core.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One value of Minecraft's NBT format, as read by {@link NbtReader}. Arrays are kept as the
 * primitive arrays the file holds (a design's blocks are one big array) and are never copied:
 * readers treat them as read-only.
 */
public sealed interface NbtTag {
  /** The number this tag holds, or empty for a non-numeric tag. */
  default Optional<Number> number() {
    return Optional.empty();
  }

  record ByteTag(byte value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record ShortTag(short value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record IntTag(int value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record LongTag(long value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record FloatTag(float value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record DoubleTag(double value) implements NbtTag {
    @Override
    public Optional<Number> number() {
      return Optional.of(value);
    }
  }

  record StringTag(String value) implements NbtTag {}

  /** Read-only: the reader's own array. */
  record ByteArrayTag(byte[] value) implements NbtTag {}

  /** Read-only: the reader's own array. */
  record IntArrayTag(int[] value) implements NbtTag {}

  /** Read-only: the reader's own array. */
  record LongArrayTag(long[] value) implements NbtTag {}

  record ListTag(List<NbtTag> values) implements NbtTag {
    public ListTag {
      values = List.copyOf(values);
    }

    /** The compounds of the list, in order; other elements are skipped. */
    public List<CompoundTag> compounds() {
      return values.stream()
          .filter(CompoundTag.class::isInstance)
          .map(CompoundTag.class::cast)
          .toList();
    }
  }

  /** Named values in file order. */
  record CompoundTag(Map<String, NbtTag> values) implements NbtTag {
    public CompoundTag {
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public @Nullable NbtTag get(String key) {
      return values.get(key);
    }

    public boolean contains(String key) {
      return values.containsKey(key);
    }

    /** The number under {@code key} as an int (any numeric tag), or {@code fallback}. */
    public int intValue(String key, int fallback) {
      NbtTag tag = values.get(key);
      return tag == null ? fallback : tag.number().map(Number::intValue).orElse(fallback);
    }

    public Optional<String> string(String key) {
      return values.get(key) instanceof StringTag(String value)
          ? Optional.of(value)
          : Optional.empty();
    }

    public Optional<CompoundTag> compound(String key) {
      return values.get(key) instanceof CompoundTag compound
          ? Optional.of(compound)
          : Optional.empty();
    }

    /** The list under {@code key}, or an empty list. */
    public List<NbtTag> list(String key) {
      return values.get(key) instanceof ListTag(List<NbtTag> list) ? list : List.of();
    }

    /** The compounds in the list under {@code key}, or an empty list. */
    public List<CompoundTag> compounds(String key) {
      return values.get(key) instanceof ListTag list ? list.compounds() : List.of();
    }

    public byte @Nullable [] byteArray(String key) {
      return values.get(key) instanceof ByteArrayTag(byte[] value) ? value : null;
    }

    public int @Nullable [] intArray(String key) {
      return values.get(key) instanceof IntArrayTag(int[] value) ? value : null;
    }

    public long @Nullable [] longArray(String key) {
      return values.get(key) instanceof LongArrayTag(long[] value) ? value : null;
    }

    /** The same compound with {@code key} set to {@code value} (in place when already there). */
    public CompoundTag with(String key, NbtTag value) {
      Map<String, NbtTag> changed = new LinkedHashMap<>(values);
      changed.put(key, value);
      return new CompoundTag(changed);
    }

    /** The same compound without {@code keys}. */
    public CompoundTag without(Set<String> keys) {
      Map<String, NbtTag> kept = new LinkedHashMap<>(values);
      kept.keySet().removeAll(keys);
      return new CompoundTag(kept);
    }
  }
}
