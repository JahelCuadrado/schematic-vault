package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockRenamesTest {
  @Test
  void renamesBlocksTheGameNoLongerKnows() {
    assertEquals(
        "minecraft:iron_chain", BlockRenames.currentName("minecraft:chain", Map.of("axis", "y")));
    assertEquals("minecraft:short_grass", BlockRenames.currentName("minecraft:grass", Map.of()));
    assertEquals(
        "minecraft:powered_rail", BlockRenames.currentName("minecraft:golden_rail", Map.of()));
  }

  @Test
  void colouredLegacyBlocksTakeTheirColour() {
    assertEquals(
        "minecraft:red_wool", BlockRenames.currentName("minecraft:wool", Map.of("color", "red")));
    assertEquals(
        "minecraft:light_gray_terracotta",
        BlockRenames.currentName("minecraft:stained_hardened_clay", Map.of("color", "silver")));
    assertEquals(
        "minecraft:terracotta", BlockRenames.currentName("minecraft:hardened_clay", Map.of()));
  }

  @Test
  void leavesOtherNamesAsTheyAre() {
    assertEquals("minecraft:stone", BlockRenames.currentName("minecraft:stone", Map.of()));
    assertEquals("minecraft:unknown", BlockRenames.currentName("minecraft:unknown", Map.of()));
    assertEquals("create:chain", BlockRenames.currentName("create:chain", Map.of()));
    assertEquals("create:log", BlockRenames.currentName("create:log", Map.of("variant", "birch")));
  }

  @Test
  void renamesThePreFlatteningNames() {
    assertEquals("minecraft:cobweb", BlockRenames.currentName("minecraft:web", Map.of()));
    assertEquals("minecraft:dirt_path", BlockRenames.currentName("minecraft:grass_path", Map.of()));
    assertEquals("minecraft:magma_block", BlockRenames.currentName("minecraft:magma", Map.of()));
    assertEquals(
        "minecraft:nether_quartz_ore", BlockRenames.currentName("minecraft:quartz_ore", Map.of()));
    assertEquals(
        "minecraft:nether_bricks", BlockRenames.currentName("minecraft:nether_brick", Map.of()));
    assertEquals(
        "minecraft:light_gray_shulker_box",
        BlockRenames.currentName("minecraft:silver_shulker_box", Map.of("facing", "up")));
  }

  @Test
  void aPropertyPicksTheWood() {
    assertEquals(
        "minecraft:birch_log",
        BlockRenames.currentName("minecraft:log", Map.of("variant", "birch")));
    assertEquals(
        "minecraft:dark_oak_log",
        BlockRenames.currentName("minecraft:log2", Map.of("variant", "dark_oak")));
    assertEquals(
        "minecraft:spruce_slab",
        BlockRenames.currentName("minecraft:wooden_slab", Map.of("variant", "spruce")));
    assertEquals(
        "minecraft:jungle_planks",
        BlockRenames.currentName("minecraft:planks", Map.of("variant", "jungle")));
    assertEquals(
        "minecraft:acacia_sapling",
        BlockRenames.currentName("minecraft:sapling", Map.of("type", "acacia")));
  }

  @Test
  void withoutThePropertyTheFirstBlockOfTheOldIdIsUsed() {
    assertEquals("minecraft:oak_log", BlockRenames.currentName("minecraft:log", Map.of()));
    assertEquals("minecraft:acacia_log", BlockRenames.currentName("minecraft:log2", Map.of()));
    assertEquals("minecraft:oak_planks", BlockRenames.currentName("minecraft:planks", Map.of()));
    assertEquals("minecraft:white_wool", BlockRenames.currentName("minecraft:wool", Map.of()));
    assertEquals(
        "minecraft:white_stained_glass",
        BlockRenames.currentName("minecraft:stained_glass", Map.of()));
    assertEquals(
        "minecraft:smooth_stone_slab",
        BlockRenames.currentName("minecraft:double_stone_slab", Map.of()));
  }

  @Test
  void aPropertyNamesTheBlock() {
    assertEquals(
        "minecraft:quartz_slab",
        BlockRenames.currentName("minecraft:double_stone_slab", Map.of("variant", "quartz")));
    assertEquals(
        "minecraft:azure_bluet",
        BlockRenames.currentName("minecraft:red_flower", Map.of("type", "houstonia")));
    assertEquals(
        "minecraft:infested_stone_bricks",
        BlockRenames.currentName("minecraft:monster_egg", Map.of("variant", "stone_brick")));
    assertEquals(
        "minecraft:short_grass",
        BlockRenames.currentName("minecraft:tallgrass", Map.of("type", "tall_grass")));
  }
}
