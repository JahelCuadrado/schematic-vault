package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The core's NBT as the game's own tags, for the game's codecs and data fixers, and back (for NBT
 * the data fixer upgraded before the core writes it).
 */
final class VanillaNbt {
  /** The key the game wraps a mixed list's elements under. */
  private static final String WRAPPED_KEY = "";

  private VanillaNbt() {}

  static CompoundTag compound(NbtTag.CompoundTag compound) {
    CompoundTag tag = new CompoundTag();
    for (Map.Entry<String, NbtTag> entry : compound.values().entrySet()) {
      tag.put(entry.getKey(), tag(entry.getValue()));
    }
    return tag;
  }

  /** The arrays are copied: the game's tags may be changed by data fixers. */
  private static Tag tag(NbtTag value) {
    return switch (value) {
      case NbtTag.ByteTag(byte data) -> ByteTag.valueOf(data);
      case NbtTag.ShortTag(short data) -> ShortTag.valueOf(data);
      case NbtTag.IntTag(int data) -> IntTag.valueOf(data);
      case NbtTag.LongTag(long data) -> LongTag.valueOf(data);
      case NbtTag.FloatTag(float data) -> FloatTag.valueOf(data);
      case NbtTag.DoubleTag(double data) -> DoubleTag.valueOf(data);
      case NbtTag.StringTag(String data) -> StringTag.valueOf(data);
      case NbtTag.ByteArrayTag(byte[] data) -> new ByteArrayTag(data.clone());
      case NbtTag.IntArrayTag(int[] data) -> new IntArrayTag(data.clone());
      case NbtTag.LongArrayTag(long[] data) -> new LongArrayTag(data.clone());
      case NbtTag.CompoundTag compound -> compound(compound);
      case NbtTag.ListTag(List<NbtTag> values) -> {
        ListTag list = new ListTag();
        for (NbtTag element : values) {
          list.add(tag(element));
        }
        yield list;
      }
    };
  }

  /** The game's compound as the core's; end tags (never inside a compound) are left out. */
  static NbtTag.CompoundTag core(CompoundTag compound) {
    Map<String, NbtTag> values = new LinkedHashMap<>();
    for (String key : compound.keySet()) {
      Tag value = compound.get(key);
      NbtTag converted = value == null ? null : core(value);
      if (converted != null) {
        values.put(key, converted);
      }
    }
    return new NbtTag.CompoundTag(values);
  }

  private static @Nullable NbtTag core(Tag value) {
    return switch (value) {
      case ByteTag(byte data) -> new NbtTag.ByteTag(data);
      case ShortTag(short data) -> new NbtTag.ShortTag(data);
      case IntTag(int data) -> new NbtTag.IntTag(data);
      case LongTag(long data) -> new NbtTag.LongTag(data);
      case FloatTag(float data) -> new NbtTag.FloatTag(data);
      case DoubleTag(double data) -> new NbtTag.DoubleTag(data);
      case StringTag(String data) -> new NbtTag.StringTag(data);
      case ByteArrayTag bytes -> new NbtTag.ByteArrayTag(bytes.getAsByteArray().clone());
      case IntArrayTag ints -> new NbtTag.IntArrayTag(ints.getAsIntArray().clone());
      case LongArrayTag longs -> new NbtTag.LongArrayTag(longs.getAsLongArray().clone());
      case CompoundTag compound -> core(compound);
      case ListTag list -> {
        List<NbtTag> elements = new ArrayList<>(list.size());
        for (Tag element : list) {
          NbtTag converted = core(element);
          if (converted != null) {
            elements.add(converted);
          }
        }
        yield new NbtTag.ListTag(homogeneous(elements));
      }
      default -> null;
    };
  }

  /**
   * The elements as one tag type: the game's lists may mix types, which it saves by wrapping each
   * element that is not a compound into one under an empty key ({@code ListTag#wrapElement}).
   */
  private static List<NbtTag> homogeneous(List<NbtTag> elements) {
    boolean mixed = false;
    for (NbtTag element : elements) {
      mixed |= element.getClass() != elements.getFirst().getClass();
    }
    if (!mixed) {
      return elements;
    }
    List<NbtTag> wrapped = new ArrayList<>(elements.size());
    for (NbtTag element : elements) {
      wrapped.add(
          element instanceof NbtTag.CompoundTag
              ? element
              : new NbtTag.CompoundTag(Map.of(WRAPPED_KEY, element)));
    }
    return wrapped;
  }
}
