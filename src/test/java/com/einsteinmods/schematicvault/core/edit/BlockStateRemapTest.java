package com.einsteinmods.schematicvault.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.PropertyValues;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.Remapped;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.TargetBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockStateRemapTest {
  /** Vanilla's stair properties, in the block's order. */
  static TargetBlock stairs(String id) {
    Map<String, PropertyValues> properties = new LinkedHashMap<>();
    properties.put(
        "facing", new PropertyValues(List.of("north", "south", "west", "east"), "north"));
    properties.put("half", new PropertyValues(List.of("top", "bottom"), "bottom"));
    properties.put(
        "shape",
        new PropertyValues(
            List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
            "straight"));
    properties.put("waterlogged", new PropertyValues(List.of("true", "false"), "false"));
    return new TargetBlock(id, properties);
  }

  private static final Map<String, String> OAK_STAIRS =
      Map.of("facing", "east", "half", "top", "shape", "outer_left", "waterlogged", "true");

  @Test
  void stairsToStairsKeepsEveryProperty() {
    Remapped remapped = BlockStateRemap.remap(OAK_STAIRS, stairs("minecraft:spruce_stairs"));

    assertEquals(
        "minecraft:spruce_stairs[facing=east,half=top,shape=outer_left,waterlogged=true]",
        remapped.state());
    assertFalse(remapped.lostProperties());
  }

  @Test
  void stairsToAFullBlockDropsThemAll() {
    Remapped remapped = BlockStateRemap.remap(OAK_STAIRS, TargetBlock.plain("minecraft:stone"));

    assertEquals("minecraft:stone", remapped.state());
    assertTrue(remapped.lostProperties());
  }

  @Test
  void anInvalidValueFallsBackToTheDefault() {
    Map<String, PropertyValues> slab = new LinkedHashMap<>();
    slab.put("type", new PropertyValues(List.of("top", "bottom", "double"), "bottom"));
    slab.put("waterlogged", new PropertyValues(List.of("true", "false"), "false"));
    Remapped remapped =
        BlockStateRemap.remap(
            Map.of("type", "sideways", "waterlogged", "true"),
            new TargetBlock("minecraft:oak_slab", slab));

    assertEquals("minecraft:oak_slab[type=bottom,waterlogged=true]", remapped.state());
    assertTrue(remapped.lostProperties());
  }

  @Test
  void aBlockWithoutPropertiesLosesNothing() {
    Remapped remapped =
        BlockStateRemap.remap(Map.of(), TargetBlock.plain("minecraft:spruce_planks"));

    assertEquals("minecraft:spruce_planks", remapped.state());
    assertFalse(remapped.lostProperties());
  }

  @Test
  void overlayKeepsTheValuesTheBlockAccepts() {
    Map<String, String> fixed = Map.of("facing", "north", "half", "bottom", "shape", "straight");
    Map<String, String> file =
        Map.of("facing", "west", "half", "up_z", "shape", "outer_left", "variant", "oak");

    assertEquals(
        Map.of("facing", "west", "half", "bottom", "shape", "outer_left"),
        BlockStateRemap.overlay(fixed, file, stairs("minecraft:oak_stairs")));
  }
}
