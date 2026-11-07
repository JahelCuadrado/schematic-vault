package com.einsteinmods.schematicvault.core.volume;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import org.junit.jupiter.api.Test;

class LitematicReaderTest {
  /** Five palette entries: three bits per cell, so some cells span two longs. */
  private static final NbtTag PALETTE =
      list(
          compound("Name", string("minecraft:air")),
          compound("Name", string("minecraft:stone")),
          compound(
              "Name",
              string("minecraft:oak_stairs"),
              "Properties",
              compound("facing", string("east"), "half", string("top"))),
          compound("Name", string("minecraft:chest")),
          compound("Name", string("minecraft:glass")));

  static long[] pack(int[] values, int bits) {
    long[] packed = new long[(values.length * bits + Long.SIZE - 1) / Long.SIZE];
    for (int index = 0; index < values.length; index++) {
      long bitIndex = (long) index * bits;
      int word = (int) (bitIndex / Long.SIZE);
      int shift = (int) (bitIndex % Long.SIZE);
      packed[word] |= (long) values[index] << shift;
      if (shift + bits > Long.SIZE) {
        packed[word + 1] |= (long) values[index] >>> (Long.SIZE - shift);
      }
    }
    return packed;
  }

  private static CompoundTag region(
      int x, int y, int z, int sizeX, int sizeY, int sizeZ, int[] cells, NbtTag tileEntities) {
    return compound(
        "Position",
        compound("x", integer(x), "y", integer(y), "z", integer(z)),
        "Size",
        compound("x", integer(sizeX), "y", integer(sizeY), "z", integer(sizeZ)),
        "BlockStatePalette",
        PALETTE,
        "BlockStates",
        new LongArrayTag(pack(cells, 3)),
        "TileEntities",
        tileEntities);
  }

  private static byte[] file(CompoundTag regions, int version) {
    return NbtFixtures.gzip(
        compound(
            "Version", integer(version),
            "MinecraftDataVersion", integer(3700),
            "Regions", regions));
  }

  @Test
  void mergesRegionsWithNegativeSizesAndCountsBlocksAsTheApi() throws VolumeFormatException {
    // 4 x 2 x 3 = 24 cells: index = (y * 3 + z) * 4 + x.
    int[] first = new int[24];
    for (int index = 0; index < first.length; index++) {
      first[index] = index % 5;
    }
    // A region at x 10 with size -2 covers x 9 and 10.
    int[] second = {1, 4};
    CompoundTag regions =
        compound(
            "main",
            region(
                0,
                0,
                0,
                4,
                2,
                3,
                first,
                list(
                    compound(
                        "x",
                        integer(3),
                        "y",
                        integer(0),
                        "z",
                        integer(0),
                        "id",
                        string("minecraft:chest")))),
            "side",
            region(10, 0, 0, -2, 1, 1, second, list()));
    VoxelVolume volume =
        SchematicReaders.read(SchematicFormat.LITEMATIC, file(regions, 6), LegacyBlocks::load);

    assertEquals(11, volume.sizeX());
    assertEquals(2, volume.sizeY());
    assertEquals(3, volume.sizeZ());
    long nonAir = 0;
    for (int value : first) {
      if (value != 0) {
        nonAir++;
      }
    }
    assertEquals(nonAir + 2, volume.blockCount());
    assertEquals(
        "minecraft:oak_stairs[facing=east,half=top]",
        volume.palette().get(volume.cells()[volume.index(2, 0, 0)]));
    assertEquals("minecraft:stone", volume.palette().get(volume.cells()[volume.index(9, 0, 0)]));
    assertEquals("minecraft:glass", volume.palette().get(volume.cells()[volume.index(10, 0, 0)]));
    assertEquals(VoxelVolume.AIR, volume.cells()[volume.index(5, 0, 0)]);
    assertEquals(3700, volume.dataVersion());
    CompoundTag chest = volume.blockEntities().get(volume.index(3, 0, 0));
    assertEquals("minecraft:chest", chest.string("id").orElseThrow());
    assertTrue(!chest.contains("x"), "positions are dropped");
  }

  @Test
  void refusesUnknownVersionsAndShortArrays() {
    CompoundTag regions = compound("main", region(0, 0, 0, 4, 2, 3, new int[24], list()));
    assertThrows(
        VolumeFormatException.class,
        () ->
            SchematicReaders.read(
                SchematicFormat.LITEMATIC, file(regions, 99), LegacyBlocks::load));
    CompoundTag shortRegion = compound("main", region(0, 0, 0, 40, 2, 3, new int[24], list()));
    assertThrows(
        VolumeFormatException.class,
        () ->
            SchematicReaders.read(
                SchematicFormat.LITEMATIC, file(shortRegion, 6), LegacyBlocks::load));
  }

  @Test
  void refusesABoxPastTheCellLimit() {
    CompoundTag regions =
        compound(
            "a", region(0, 0, 0, 1, 1, 1, new int[] {1}, list()),
            "b", region(4000, 4000, 4000, 1, 1, 1, new int[] {1}, list()));
    VolumeFormatException failure =
        assertThrows(
            VolumeFormatException.class,
            () ->
                SchematicReaders.read(
                    SchematicFormat.LITEMATIC, file(regions, 6), LegacyBlocks::load));
    assertTrue(failure.tooLarge());
  }
}
