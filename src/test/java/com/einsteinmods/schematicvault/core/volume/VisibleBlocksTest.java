package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VisibleBlocksTest {
  private static final int STONE = 1;
  private static final int GLASS = 2;
  private static final boolean[] OPAQUE = {false, true, false};

  private static VoxelVolume cube(int size, int fill) {
    int[] cells = new int[size * size * size];
    java.util.Arrays.fill(cells, fill);
    int[] counts = new int[3];
    counts[fill] = cells.length;
    return new VoxelVolume(
        size,
        size,
        size,
        List.of(VoxelVolume.AIR_STATE, "minecraft:stone", "minecraft:glass"),
        cells,
        counts,
        Map.of(),
        0,
        0,
        cells.length);
  }

  private static VoxelVolume withCell(VoxelVolume volume, int x, int y, int z, int state) {
    int[] cells = volume.cells().clone();
    int[] counts = volume.paletteCounts().clone();
    int index = volume.index(x, y, z);
    counts[cells[index]]--;
    counts[state]++;
    cells[index] = state;
    return new VoxelVolume(
        volume.sizeX(),
        volume.sizeY(),
        volume.sizeZ(),
        volume.palette(),
        cells,
        counts,
        Map.of(),
        0,
        0,
        volume.blockCount());
  }

  @Test
  void hidesOnlyTheBlocksEnclosedByOpaqueBlocks() {
    VoxelVolume solid = cube(3, STONE);
    int[] visible = VisibleBlocks.of(solid, OPAQUE);
    assertEquals(26, visible.length);
    for (int index : visible) {
      assertEquals(true, index != solid.index(1, 1, 1));
    }
  }

  @Test
  void aTransparentNeighbourShowsTheBlockBehindIt() {
    VoxelVolume volume = withCell(cube(5, STONE), 1, 2, 2, GLASS);
    int[] visible = VisibleBlocks.of(volume, OPAQUE);
    // 98 border cells and the five inner stones the glass touches; the glass itself is enclosed by
    // stone on all sides, so it cannot be seen either.
    assertEquals(98 + 5, visible.length);
  }

  @Test
  void airIsNeverVisibleAndTransparentBlocksAlwaysAre() {
    VoxelVolume volume = withCell(cube(3, GLASS), 0, 0, 0, VoxelVolume.AIR);
    int[] visible = VisibleBlocks.of(volume, OPAQUE);
    assertEquals(26, visible.length);
    assertArrayEquals(
        java.util.stream.IntStream.range(1, 27).toArray(), visible, "ascending, air skipped");
  }

  @Test
  void aCutShowsTheTopOfItsLastLayer() {
    VoxelVolume solid = cube(5, STONE);
    int[] visible = VisibleBlocks.of(solid, OPAQUE, new LayerRange(0, 2));
    // Layers 0-2 of a 5-cube: 25 bottom, 25 on the cut top, the 16 sides of the middle layer.
    assertEquals(25 + 25 + 16, visible.length);
    for (int x = 1; x < 4; x++) {
      for (int z = 1; z < 4; z++) {
        int top = solid.index(x, 2, z);
        assertEquals(
            true,
            java.util.Arrays.stream(visible).anyMatch(index -> index == top),
            "inner block under the cut");
      }
    }
    for (int index : visible) {
      assertEquals(true, index < solid.index(0, 3, 0), "nothing above the cut");
    }
  }

  @Test
  void aCutAtTheTopIsTheWholeDesignAndAtZeroIsNothing() {
    VoxelVolume volume = withCell(cube(5, STONE), 1, 2, 2, GLASS);
    assertArrayEquals(
        VisibleBlocks.of(volume, OPAQUE), VisibleBlocks.of(volume, OPAQUE, new LayerRange(0, 4)));
    assertArrayEquals(
        VisibleBlocks.of(volume, OPAQUE), VisibleBlocks.of(volume, OPAQUE, new LayerRange(0, 99)));
    assertEquals(0, VisibleBlocks.of(volume, OPAQUE, new LayerRange(5, 9)).length);
    assertEquals(25, VisibleBlocks.of(volume, OPAQUE, new LayerRange(0, 0)).length);
  }

  @Test
  void aBottomCutShowsTheUndersideOfItsLowestLayer() {
    VoxelVolume solid = cube(5, STONE);
    int[] visible = VisibleBlocks.of(solid, OPAQUE, new LayerRange(2, 4));
    // The mirror of keeping layers 0-2: 25 on the cut, 25 on top, the 16 sides of the middle layer.
    assertEquals(25 + 25 + 16, visible.length);
    int overCut = solid.index(2, 2, 2);
    assertEquals(
        true,
        java.util.Arrays.stream(visible).anyMatch(index -> index == overCut),
        "inner block over the cut");
    for (int index : visible) {
      assertEquals(true, index >= solid.index(0, 2, 0), "nothing below the cut");
    }
  }

  @Test
  void aRangeHidesOnlyBlocksEnclosedInsideIt() {
    VoxelVolume solid = cube(5, STONE);
    int[] visible = VisibleBlocks.of(solid, OPAQUE, new LayerRange(1, 3));
    // Three layers: both outer ones whole (25 each) and the middle one's 16 sides; (2, 2, 2) and
    // the other inner cells of the middle layer stay enclosed.
    assertEquals(25 + 25 + 16, visible.length);
    int enclosed = solid.index(2, 2, 2);
    for (int index : visible) {
      assertEquals(true, index != enclosed);
    }
    int[] singleLayer = VisibleBlocks.of(solid, OPAQUE, new LayerRange(3, 3));
    assertEquals(25, singleLayer.length, "one layer shows whole");
    assertEquals(solid.index(0, 3, 0), singleLayer[0]);
  }

  @Test
  void aRangeIsCutToTheVolume() {
    VoxelVolume solid = cube(5, STONE);
    assertArrayEquals(
        VisibleBlocks.of(solid, OPAQUE, new LayerRange(3, 4)),
        VisibleBlocks.of(solid, OPAQUE, new LayerRange(3, 40)));
    assertArrayEquals(
        VisibleBlocks.of(solid, OPAQUE), VisibleBlocks.of(solid, OPAQUE, LayerRange.ALL));
  }

  @Test
  void eachLayerReadsThePaletteOfItsBand() {
    VoxelVolume solid = cube(3, STONE);
    boolean[][] opaqueByBand = {OPAQUE, {false, false, false}};

    // The top layer's stone became glass: the middle block now shows.
    int[] visible = VisibleBlocks.of(solid, opaqueByBand, new int[] {0, 0, 1}, LayerRange.ALL);

    assertEquals(27, visible.length);
  }

  @Test
  void aBandBelowTheOpaqueBlocksShowsThroughToo() {
    VoxelVolume solid = cube(3, STONE);
    boolean[][] opaqueByBand = {{false, false, false}, OPAQUE};

    int[] visible = VisibleBlocks.of(solid, opaqueByBand, new int[] {0, 1, 1}, LayerRange.ALL);

    assertEquals(27, visible.length);
  }
}
