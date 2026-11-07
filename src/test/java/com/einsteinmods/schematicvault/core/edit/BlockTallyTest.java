package com.einsteinmods.schematicvault.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockTallyTest {
  /** The palette of {@link #layered}: both stair states count as one block. */
  private static final String[] LAYERED_IDS = {
    null,
    "minecraft:dirt",
    "minecraft:stone",
    "minecraft:glass",
    "minecraft:oak_stairs",
    "minecraft:oak_stairs"
  };

  /** Two by two by four: dirt and air, stone and glass, stone and a stair, air on top. */
  private static VoxelVolume layered() {
    List<String> palette =
        List.of(
            "minecraft:air",
            "minecraft:dirt",
            "minecraft:stone",
            "minecraft:glass",
            "minecraft:oak_stairs[facing=east]",
            "minecraft:oak_stairs[facing=west]");
    int[] cells = {1, 1, 1, 0, 2, 3, 0, 0, 2, 0, 0, 4, 0, 0, 0, 0};
    int[] counts = {9, 3, 2, 1, 1, 0};
    return new VoxelVolume(2, 4, 2, palette, cells, counts, Map.of(), 0, 0, 7);
  }

  @Test
  void countsEveryStateOfABlockTogetherAndLeavesAirOut() {
    List<String> palette =
        List.of(
            "minecraft:air",
            "minecraft:oak_stairs[facing=east]",
            "minecraft:stone",
            "minecraft:oak_stairs[facing=west]",
            "minecraft:cave_air");
    int[] cells = {0, 1, 1, 2, 3, 3, 3, 0, 4, 2};
    int[] counts = {2, 2, 2, 3, 1};
    VoxelVolume volume = new VoxelVolume(10, 1, 1, palette, cells, counts, Map.of(), 0, 0, 7);

    BlockTally tally = BlockTally.of(volume);

    assertEquals(
        List.of(
            new BlockTally.Entry("minecraft:oak_stairs", 5),
            new BlockTally.Entry("minecraft:stone", 2)),
        tally.entries());
    assertEquals(7, tally.total());
    assertEquals(tally.total(), tally.entries().stream().mapToLong(BlockTally.Entry::count).sum());
  }

  @Test
  void aRangeCountsOnlyItsLayersAndDropsBlocksAbsentFromThem() {
    BlockTally range = BlockTally.of(LAYERED_IDS, layered(), new LayerRange(1, 2));

    assertEquals(
        List.of(
            new BlockTally.Entry("minecraft:stone", 2),
            new BlockTally.Entry("minecraft:glass", 1),
            new BlockTally.Entry("minecraft:oak_stairs", 1)),
        range.entries());
    assertEquals(4, range.total());
  }

  @Test
  void aSingleLayerAndAnEmptyLayerAreRangesToo() {
    assertEquals(
        List.of(new BlockTally.Entry("minecraft:dirt", 3)),
        BlockTally.of(LAYERED_IDS, layered(), new LayerRange(0, 0)).entries());
    BlockTally empty = BlockTally.of(LAYERED_IDS, layered(), new LayerRange(3, 3));
    assertTrue(empty.entries().isEmpty());
    assertEquals(0, empty.total());
  }

  @Test
  void rangesAddUpToTheWholeDesign() {
    VoxelVolume volume = layered();
    BlockTally whole = BlockTally.of(LAYERED_IDS, volume.paletteCounts());

    assertEquals(whole, BlockTally.of(LAYERED_IDS, volume, LayerRange.ALL));
    assertEquals(whole, BlockTally.of(LAYERED_IDS, volume, new LayerRange(0, 3)));
    for (int split = 0; split < volume.sizeY() - 1; split++) {
      long below = BlockTally.of(LAYERED_IDS, volume, new LayerRange(0, split)).total();
      long above = BlockTally.of(LAYERED_IDS, volume, new LayerRange(split + 1, 3)).total();
      assertEquals(whole.total(), below + above);
    }
  }

  @Test
  void tiesAreSortedById() {
    BlockTally tally =
        BlockTally.of(new String[] {null, "minecraft:b", "minecraft:a"}, new int[] {9, 4, 4});

    assertEquals("minecraft:a", tally.entries().getFirst().blockId());
    assertEquals(8, tally.total());
  }

  @Test
  void shapesComeFromTheLongestSuffix() {
    assertEquals("_stairs", BlockShapes.suffix("minecraft:oak_stairs").orElseThrow());
    assertEquals("_wall_sign", BlockShapes.suffix("minecraft:oak_wall_sign").orElseThrow());
    assertEquals("_glass", BlockShapes.suffix("minecraft:glass").orElseThrow());
    assertTrue(BlockShapes.sameShape("minecraft:white_wool", "minecraft:red_wool"));
    assertTrue(BlockShapes.suffix("minecraft:stone").isEmpty());
  }

  private static RuleBands bands(BlockRule... rules) {
    return RuleBands.of(List.of(rules), 4);
  }

  @Test
  void throughBandsABlockShowsTheNewBlockOnItsLayersAndTheOldElsewhere() {
    BlockRule rule = new BlockRule("minecraft:stone", "minecraft:glass", new LayerRange(1, 1));

    BlockTally tally = BlockTally.of(LAYERED_IDS, layered(), LayerRange.ALL, bands(rule));

    assertEquals(7, tally.total());
    assertTrue(tally.entries().contains(new BlockTally.Entry("minecraft:stone", 1)));
    assertTrue(tally.entries().contains(new BlockTally.Entry("minecraft:stone", 1, rule)));
    assertEquals("minecraft:glass", new BlockTally.Entry("minecraft:stone", 1, rule).resultBlock());
    assertEquals(4, tally.resultTypes(), "dirt, stone, glass, stairs");
  }

  @Test
  void throughBandsOnlyTheRangeIsCounted() {
    BlockRule rule = new BlockRule("minecraft:stone", "minecraft:glass", new LayerRange(1, 1));

    BlockTally tally = BlockTally.of(LAYERED_IDS, layered(), new LayerRange(1, 1), bands(rule));

    assertEquals(
        List.of(
            new BlockTally.Entry("minecraft:glass", 1),
            new BlockTally.Entry("minecraft:stone", 1, rule)),
        tally.entries());
    assertEquals(1, tally.resultTypes(), "the stone became glass");
  }

  @Test
  void cellsARuleRemovesKeepAnEntryButLeaveTheTotal() {
    BlockRule rule = new BlockRule("minecraft:dirt", "minecraft:air", LayerRange.ALL);

    BlockTally tally = BlockTally.of(LAYERED_IDS, layered(), LayerRange.ALL, bands(rule));

    assertEquals(4, tally.total());
    assertTrue(tally.entries().contains(new BlockTally.Entry("minecraft:dirt", 3, rule)));
    assertEquals(3, tally.resultTypes(), "stone, glass, stairs");
  }

  @Test
  void twoRulesOfOneBlockHaveTheirOwnEntries() {
    BlockRule low = new BlockRule("minecraft:stone", "minecraft:glass", new LayerRange(1, 1));
    BlockRule high = new BlockRule("minecraft:stone", "minecraft:glass", new LayerRange(2, 3));

    BlockTally tally = BlockTally.of(LAYERED_IDS, layered(), LayerRange.ALL, bands(low, high));

    assertTrue(tally.entries().contains(new BlockTally.Entry("minecraft:stone", 1, low)));
    assertTrue(tally.entries().contains(new BlockTally.Entry("minecraft:stone", 1, high)));
  }
}
