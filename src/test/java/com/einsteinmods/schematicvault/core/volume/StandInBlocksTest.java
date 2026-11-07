package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.edit.BlockStateRemap;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.PropertyValues;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.TargetBlock;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks.Match;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks.StandIn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class StandInBlocksTest {
  /** A few vanilla ids, as the client registry would answer. */
  private static final Set<String> VANILLA =
      Set.of(
          "minecraft:air",
          "minecraft:light",
          "minecraft:stone",
          "minecraft:glass",
          "minecraft:glass_pane",
          "minecraft:stone_stairs",
          "minecraft:stone_slab",
          "minecraft:oak_log",
          "minecraft:oak_planks",
          "minecraft:oak_fence",
          "minecraft:oak_door",
          "minecraft:oak_trapdoor",
          "minecraft:lantern",
          "minecraft:hanging_roots",
          "minecraft:glowstone",
          "minecraft:bricks",
          "minecraft:chest",
          "minecraft:barrel",
          "minecraft:white_wool",
          "minecraft:oak_leaves",
          "minecraft:magenta_wool",
          "minecraft:crafter");

  private static final Predicate<String> KNOWS = VANILLA::contains;

  private static StandIn of(String id) {
    return StandInBlocks.of(id, KNOWS);
  }

  @Test
  void aVanillaIdNeverChanges() {
    assertEquals(new StandIn("minecraft:stone", Match.KNOWN), of("minecraft:stone"));
    assertEquals(new StandIn("minecraft:glass", Match.KNOWN), of("minecraft:glass"));
  }

  @Test
  void aPathEndingInAVanillaBlockTakesTheLongestOne() {
    assertEquals(
        new StandIn("minecraft:glass", Match.SUFFIX), of("connectedglass:borderless_glass"));
    assertEquals(new StandIn("minecraft:glass", Match.SUFFIX), of("connectedglass:clear_glass"));
    assertEquals(new StandIn("minecraft:lantern", Match.SUFFIX), of("adorn:candlelit_lantern"));
    assertEquals(new StandIn("minecraft:crafter", Match.SUFFIX), of("crafter_backport:crafter"));
    assertEquals(
        new StandIn("minecraft:hanging_roots", Match.SUFFIX), of("extcaves:hanging_roots"));
    assertEquals(new StandIn("minecraft:glass_pane", Match.SUFFIX), of("mod:tinted_glass_pane"));
  }

  @Test
  void onlyRealVanillaIdsMatchAsSuffixes() {
    // "hollow_log" and "log" are no vanilla blocks: the table's "log" entry answers.
    assertEquals(new StandIn("minecraft:oak_log", Match.TABLE), of("mod:oak_hollow_log"));
  }

  @Test
  void invisibleBlocksAreNeverStandIns() {
    assertEquals(new StandIn("minecraft:glowstone", Match.TABLE), of("extlights:wall_light"));
    assertEquals(new StandIn(StandInBlocks.PLACEHOLDER, Match.PLACEHOLDER), of("mod:hot_air"));
  }

  @Test
  void theTableCoversCommonShapesAndMaterials() {
    assertEquals(
        new StandIn("minecraft:glass", Match.TABLE), of("connectedglass:borderless_glass_gray"));
    assertEquals(new StandIn("minecraft:glass_pane", Match.TABLE), of("mod:clear_pane_thin"));
    assertEquals(new StandIn("minecraft:stone_stairs", Match.TABLE), of("mod:oak_planks_stairs"));
    assertEquals(new StandIn("minecraft:stone_slab", Match.TABLE), of("mod:marble_slab_x"));
    assertEquals(
        new StandIn("minecraft:oak_fence", Match.TABLE), of("cfm:stripped_spruce_upgraded_fence"));
    assertEquals(new StandIn("minecraft:oak_trapdoor", Match.TABLE), of("mod:iron_trapdoors"));
    assertEquals(new StandIn("minecraft:oak_door", Match.TABLE), of("mod:sliding_doors"));
    assertEquals(new StandIn("minecraft:barrel", Match.TABLE), of("cfm:oak_crate"));
    assertEquals(new StandIn("minecraft:oak_log", Match.TABLE), of("fruittrees:citrus_log"));
    assertEquals(new StandIn("minecraft:oak_leaves", Match.TABLE), of("fruittrees:lime_leaves"));
    assertEquals(new StandIn("minecraft:stone", Match.TABLE), of("waystones:redstone_sharestone"));
    assertEquals(new StandIn("minecraft:stone", Match.TABLE), of("extcaves:stone_limestone"));
    assertEquals(new StandIn("minecraft:glowstone", Match.TABLE), of("mod:glowstone_lamp"));
  }

  @Test
  void anythingElseIsTheVisiblePlaceholder() {
    StandIn placeholder = new StandIn(StandInBlocks.PLACEHOLDER, Match.PLACEHOLDER);
    assertEquals(placeholder, of("create:cogwheel"));
    assertEquals(placeholder, of("legacy:416"));
    assertEquals(placeholder, of("minecraft:some_removed_block"));
  }

  @Test
  void aStandInKeepsThePropertiesItSupports() {
    StandIn stairs = of("mod:marble_stairs");
    Map<String, PropertyValues> properties = new LinkedHashMap<>();
    properties.put(
        "facing", new PropertyValues(List.of("north", "south", "west", "east"), "north"));
    properties.put("half", new PropertyValues(List.of("top", "bottom"), "bottom"));
    properties.put("shape", new PropertyValues(List.of("straight", "inner_left"), "straight"));
    Map<String, String> old = new LinkedHashMap<>();
    old.put("facing", "east");
    old.put("half", "top");
    old.put("shape", "inner_left");
    old.put("variant", "marble");
    BlockStateRemap.Remapped remapped =
        BlockStateRemap.remap(old, new TargetBlock(stairs.id(), properties));
    assertEquals("minecraft:stone_stairs[facing=east,half=top,shape=inner_left]", remapped.state());
  }

  @Test
  void modNamesAreReadableAndOldBlocksApart() {
    List<String> ids =
        List.of(
            "connectedglass:clear_glass",
            "waystones:redstone_sharestone",
            "connectedglass:borderless_glass",
            "fruit-trees:lime_leaves",
            "legacy:416");
    assertEquals(
        List.of("Connectedglass", "Fruit Trees", "Waystones"), StandInBlocks.modNames(ids));
    assertTrue(StandInBlocks.hasOldBlocks(ids));
    assertFalse(StandInBlocks.hasOldBlocks(List.of("create:belt")));
    assertTrue(StandInBlocks.isOldBlock("minecraft:some_removed_block"));
    assertEquals("Redstone Sharestone", StandInBlocks.readable("redstone_sharestone"));
  }

  @Test
  void longNameListsEndInAMarker() {
    List<String> names = List.of("A", "B", "C", "D", "E");
    assertEquals(List.of("A", "B", "C", "D", "…"), StandInBlocks.firstNames(names, 4, "…"));
    assertEquals(List.of("A", "B"), StandInBlocks.firstNames(List.of("A", "B"), 4, "…"));
  }
}
