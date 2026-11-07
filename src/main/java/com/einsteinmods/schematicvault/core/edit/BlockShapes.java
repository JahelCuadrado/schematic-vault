package com.einsteinmods.schematicvault.core.edit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The shape a block id names by its last words ({@code spruce_stairs} is a stair, {@code
 * white_wool} a wool block), for the picker's "same shape" suggestions. The longest known suffix
 * wins ({@code oak_wall_sign} is a wall sign, not a wall); an id that is the shape itself ({@code
 * glass}) has it too.
 */
public final class BlockShapes {
  private static final char NAMESPACE_SEPARATOR = ':';

  /** Known shapes, longest first. */
  private static final List<String> SUFFIXES =
      Stream.of(
              "_stairs",
              "_slab",
              "_wall",
              "_fence",
              "_fence_gate",
              "_door",
              "_trapdoor",
              "_planks",
              "_log",
              "_wood",
              "_stem",
              "_hyphae",
              "_button",
              "_pressure_plate",
              "_sign",
              "_wall_sign",
              "_hanging_sign",
              "_wall_hanging_sign",
              "_carpet",
              "_wool",
              "_bed",
              "_banner",
              "_wall_banner",
              "_concrete",
              "_concrete_powder",
              "_terracotta",
              "_glass",
              "_glass_pane",
              "_leaves",
              "_sapling",
              "_candle",
              "_shulker_box",
              "_bricks",
              "_tiles",
              "_ore",
              "_torch",
              "_wall_torch",
              "_lantern",
              "_bars",
              "_grate",
              "_bulb",
              "_coral",
              "_coral_block",
              "_coral_fan",
              "_coral_wall_fan")
          .sorted(Comparator.comparingInt(String::length).reversed())
          .toList();

  private BlockShapes() {}

  /** The shape suffix of {@code blockId} ({@code _stairs}), or empty when it names none. */
  public static Optional<String> suffix(String blockId) {
    String path = blockId.substring(blockId.indexOf(NAMESPACE_SEPARATOR) + 1);
    for (String suffix : SUFFIXES) {
      if (path.endsWith(suffix) || path.equals(suffix.substring(1))) {
        return Optional.of(suffix);
      }
    }
    return Optional.empty();
  }

  /** Whether both ids name the same shape (false when either names none). */
  public static boolean sameShape(String first, String second) {
    Optional<String> shape = suffix(first);
    return shape.isPresent() && shape.equals(suffix(second));
  }
}
