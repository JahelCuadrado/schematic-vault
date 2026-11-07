package com.einsteinmods.schematicvault.core.volume;

import java.util.Map;
import java.util.Set;

/**
 * Current names for block ids the game no longer knows: a safety net after the game's data fixer,
 * for states it leaves as they are (the flattening only matches a whole 1.12 state, so {@code
 * log[variant=birch]} without {@code axis} stays; files with a negative version are not fixed at
 * all, see {@link DataVersions}). Mirrors the API web viewer's {@code currentBlockName} ({@code
 * web/viewer.js}: {@code LEGACY_NAMES}, {@code COLOURED_LEGACY}), plus {@code grass → short_grass}
 * (1.20.3) and the flattening's table ({@code BlockStateData}): a property picks the block where it
 * says which, else the first block of the old id. Only names in the {@code minecraft} namespace are
 * renamed.
 */
public final class BlockRenames {
  private static final String NAMESPACE = "minecraft:";
  private static final String COLOR = "color";
  private static final String VARIANT = "variant";
  private static final String TYPE = "type";
  private static final String OAK = "oak";
  private static final String ACACIA = "acacia";
  private static final String WHITE = "white";
  private static final String TERRACOTTA = "terracotta";

  private static final Map<String, String> RENAMES =
      Map.ofEntries(
          Map.entry("chain", "iron_chain"),
          Map.entry("grass", "short_grass"),
          Map.entry("golden_rail", "powered_rail"),
          Map.entry("lit_pumpkin", "jack_o_lantern"),
          Map.entry("unpowered_comparator", "comparator"),
          Map.entry("powered_comparator", "comparator"),
          Map.entry("unpowered_repeater", "repeater"),
          Map.entry("powered_repeater", "repeater"),
          Map.entry("unlit_redstone_torch", "redstone_torch"),
          Map.entry("wall_sign", "oak_wall_sign"),
          Map.entry("standing_sign", "oak_sign"),
          Map.entry("stained_hardened_clay", "terracotta"),
          Map.entry("hardened_clay", "terracotta"),
          Map.entry("fence_gate", "oak_fence_gate"),
          Map.entry("trapdoor", "oak_trapdoor"),
          Map.entry("wooden_pressure_plate", "oak_pressure_plate"),
          Map.entry("lit_redstone_lamp", "redstone_lamp"),
          Map.entry("noteblock", "note_block"),
          Map.entry("melon_block", "melon"),
          Map.entry("snow_layer", "snow"),
          Map.entry("stonebrick", "stone_bricks"),
          Map.entry("wooden_button", "oak_button"),
          Map.entry("fence", "oak_fence"),
          Map.entry("slime", "slime_block"),
          Map.entry("web", "cobweb"),
          Map.entry("grass_path", "dirt_path"),
          Map.entry("magma", "magma_block"),
          Map.entry("quartz_ore", "nether_quartz_ore"),
          Map.entry("nether_brick", "nether_bricks"),
          Map.entry("red_nether_brick", "red_nether_bricks"),
          Map.entry("brick_block", "bricks"),
          Map.entry("end_bricks", "end_stone_bricks"),
          Map.entry("silver_shulker_box", "light_gray_shulker_box"),
          Map.entry("silver_glazed_terracotta", "light_gray_glazed_terracotta"),
          Map.entry("mob_spawner", "spawner"),
          Map.entry("reeds", "sugar_cane"),
          Map.entry("waterlily", "lily_pad"),
          Map.entry("yellow_flower", "dandelion"),
          Map.entry("deadbush", "dead_bush"),
          Map.entry("flowing_water", "water"),
          Map.entry("flowing_lava", "lava"),
          Map.entry("lit_furnace", "furnace"),
          Map.entry("lit_redstone_ore", "redstone_ore"),
          Map.entry("daylight_detector_inverted", "daylight_detector"),
          Map.entry("standing_banner", "white_banner"),
          Map.entry("wall_banner", "white_wall_banner"),
          Map.entry("stone_slab2", "red_sandstone_slab"),
          Map.entry("double_stone_slab2", "red_sandstone_slab"),
          Map.entry("purpur_double_slab", "purpur_slab"));

  /** Blocks that were one id per wood before 1.13: the property's value plus a suffix. */
  private static final Map<String, Suffixed> SUFFIXED =
      Map.of(
          "log", new Suffixed(VARIANT, OAK, "_log"),
          "log2", new Suffixed(VARIANT, ACACIA, "_log"),
          "planks", new Suffixed(VARIANT, OAK, "_planks"),
          "leaves", new Suffixed(VARIANT, OAK, "_leaves"),
          "leaves2", new Suffixed(VARIANT, ACACIA, "_leaves"),
          "sapling", new Suffixed(TYPE, OAK, "_sapling"),
          "wooden_slab", new Suffixed(VARIANT, OAK, "_slab"),
          "double_wooden_slab", new Suffixed(VARIANT, OAK, "_slab"));

  /** Blocks whose property names the block, with its old values as they are now called. */
  private static final Map<String, Named> NAMED =
      Map.of(
          "double_stone_slab",
          new Named(
              VARIANT,
              "smooth_stone_slab",
              Map.of(
                  "sandstone", "sandstone_slab",
                  "wood_old", "petrified_oak_slab",
                  "cobblestone", "cobblestone_slab",
                  "brick", "brick_slab",
                  "stone_brick", "stone_brick_slab",
                  "nether_brick", "nether_brick_slab",
                  "quartz", "quartz_slab")),
          "monster_egg",
          new Named(
              VARIANT,
              "infested_stone",
              Map.of(
                  "cobblestone", "infested_cobblestone",
                  "stone_brick", "infested_stone_bricks",
                  "mossy_brick", "infested_mossy_stone_bricks",
                  "cracked_brick", "infested_cracked_stone_bricks",
                  "chiseled_brick", "infested_chiseled_stone_bricks")),
          "red_flower",
          new Named(
              TYPE,
              "poppy",
              Map.of(
                  "blue_orchid", "blue_orchid",
                  "allium", "allium",
                  "houstonia", "azure_bluet",
                  "red_tulip", "red_tulip",
                  "orange_tulip", "orange_tulip",
                  "white_tulip", "white_tulip",
                  "pink_tulip", "pink_tulip",
                  "oxeye_daisy", "oxeye_daisy")),
          "tallgrass",
          new Named(TYPE, "short_grass", Map.of("fern", "fern", "dead_bush", "dead_bush")));

  /** Blocks that were one id with a {@code color} property before 1.13. */
  private static final Set<String> COLOURED =
      Set.of(
          "concrete",
          "concrete_powder",
          "wool",
          "carpet",
          "stained_glass",
          "stained_glass_pane",
          TERRACOTTA);

  private static final Map<String, String> COLOURS = Map.of("silver", "light_gray");

  private BlockRenames() {}

  /** An old id named after a property: {@code value + suffix}. */
  private record Suffixed(String property, String fallback, String suffix) {
    String name(Map<String, String> properties) {
      return properties.getOrDefault(property, fallback) + suffix;
    }
  }

  /** An old id whose property picks a block from a table; anything else is {@code fallback}. */
  private record Named(String property, String fallback, Map<String, String> names) {
    String name(Map<String, String> properties) {
      String value = properties.get(property);
      return value == null ? fallback : names.getOrDefault(value, fallback);
    }
  }

  /**
   * The current name of {@code name} (namespaced, as {@link BlockStateString#parse} gives it), or
   * {@code name} itself when the table has no entry for it.
   */
  public static String currentName(String name, Map<String, String> properties) {
    if (!name.startsWith(NAMESPACE)) {
      return name;
    }
    String path = name.substring(NAMESPACE.length());
    Suffixed suffixed = SUFFIXED.get(path);
    if (suffixed != null) {
      return NAMESPACE + suffixed.name(properties);
    }
    Named named = NAMED.get(path);
    if (named != null) {
      return NAMESPACE + named.name(properties);
    }
    String renamed = RENAMES.getOrDefault(path, path);
    if (COLOURED.contains(renamed)) {
      // Plain terracotta has no colour; the others were white before they had one.
      String color = properties.getOrDefault(COLOR, renamed.equals(TERRACOTTA) ? null : WHITE);
      if (color != null) {
        renamed = COLOURS.getOrDefault(color, color) + "_" + renamed;
      }
    }
    return renamed.equals(path) ? name : NAMESPACE + renamed;
  }
}
