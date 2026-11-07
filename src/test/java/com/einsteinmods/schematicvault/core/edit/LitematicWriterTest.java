package com.einsteinmods.schematicvault.core.edit;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.McEditReader;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LitematicWriterTest {
  private static final int DATA_VERSION = 4325;
  private static final long TIME = 1_790_000_000_000L;

  private static LitematicWriter.Metadata metadata() {
    return new LitematicWriter.Metadata("Edited", "Someone", "", TIME, DATA_VERSION);
  }

  private static VoxelVolume readBack(CompoundTag root) throws VolumeFormatException {
    return SchematicReaders.read(
        SchematicFormat.LITEMATIC, NbtFixtures.gzip(root), LegacyBlocks::load);
  }

  @Test
  void aLegacySchematicConvertsToALitematicWithItsReplacements()
      throws VolumeFormatException, IOException {
    // 4 x 1 x 1: stone, oak planks (5:0), oak stairs (53:0), a chest (54:2) holding an item.
    CompoundTag mcedit =
        compound(
            "Width", new ShortTag((short) 4),
            "Height", new ShortTag((short) 1),
            "Length", new ShortTag((short) 1),
            "Materials", string("Alpha"),
            "Blocks", new ByteArrayTag(new byte[] {1, 5, 53, 54}),
            "Data", new ByteArrayTag(new byte[] {0, 0, 0, 2}),
            "TileEntities",
                list(
                    compound(
                        "id", string("minecraft:chest"),
                        "x", integer(3),
                        "y", integer(0),
                        "z", integer(0),
                        "Items", list(compound("id", string("minecraft:apple"))))));
    VoxelVolume legacy =
        SchematicReaders.read(
            SchematicFormat.SCHEMATIC, NbtFixtures.gzip(mcedit), LegacyBlocks::load);
    String stairs = LegacyBlocks.load().state(53, 0);
    Map<String, String> replaced =
        Map.of(
            "minecraft:stone", "minecraft:air", "minecraft:oak_planks", "minecraft:spruce_planks");
    List<String> palette = new ArrayList<>();
    for (String state : legacy.palette()) {
      palette.add(replaced.getOrDefault(state, state));
    }

    CompoundTag written =
        LitematicWriter.write(
            metadata(),
            new LitematicWriter.Contents(
                legacy.sizeX(),
                legacy.sizeY(),
                legacy.sizeZ(),
                palette,
                legacy.cells(),
                McEditReader.blockEntities(mcedit)));
    VoxelVolume converted = readBack(written);

    assertEquals(VoxelVolume.AIR, converted.cells()[0]);
    assertEquals("minecraft:spruce_planks", converted.palette().get(converted.cells()[1]));
    assertEquals(stairs, converted.palette().get(converted.cells()[2]));
    assertTrue(converted.palette().get(converted.cells()[3]).startsWith("minecraft:chest"));
    assertEquals(3, converted.blockCount());
    assertEquals(DATA_VERSION, converted.dataVersion());
    CompoundTag metadata = written.compound("Metadata").orElseThrow();
    assertEquals(3, metadata.intValue("TotalBlocks", 0));
    assertEquals(4, metadata.intValue("TotalVolume", 0));
    assertEquals(1, metadata.intValue("RegionCount", 0));
    assertEquals(6, written.intValue("Version", 0));
    assertEquals(1, written.intValue("SubVersion", 0));
    CompoundTag chest = converted.blockEntities().get(3);
    assertEquals("minecraft:chest", chest.string("id").orElseThrow());
    // The volume keeps only what renderers read; the written file has the whole inventory.
    CompoundTag region = written.compound("Regions").orElseThrow().compound("Edited").orElseThrow();
    CompoundTag writtenChest = region.compounds("TileEntities").getFirst();
    assertEquals(3, writtenChest.intValue("x", -1));
    assertEquals(1, writtenChest.compounds("Items").size());
  }

  @ParameterizedTest
  @CsvSource({"2, 2", "3, 2", "17, 5", "300, 9"})
  void packsBitsAcrossLongsForEveryPaletteSize(int paletteSize, int bits)
      throws VolumeFormatException {
    List<String> palette = new ArrayList<>();
    palette.add(VoxelVolume.AIR_STATE);
    for (int index = 1; index < paletteSize; index++) {
      palette.add("minecraft:block_" + index);
    }
    // 7 x 11 x 13 = 1001 cells: every entry used, the last word only partly filled.
    int[] cells = new int[7 * 11 * 13];
    for (int index = 0; index < cells.length; index++) {
      cells[index] = (index * 7) % paletteSize;
    }
    CompoundTag written =
        LitematicWriter.write(
            metadata(), new LitematicWriter.Contents(7, 11, 13, palette, cells, Map.of()));

    CompoundTag region = written.compound("Regions").orElseThrow().compound("Edited").orElseThrow();
    assertEquals(paletteSize, region.compounds("BlockStatePalette").size());
    assertEquals(PackedStates.longs(cells.length, bits), region.longArray("BlockStates").length);
    assertEquals(bits, PackedStates.bits(paletteSize));
    VoxelVolume read = readBack(written);
    for (int index = 0; index < cells.length; index++) {
      assertEquals(palette.get(cells[index]), read.palette().get(read.cells()[index]));
    }
  }

  @Test
  void unusedAndDuplicateEntriesAreLeftOut() throws VolumeFormatException {
    List<String> palette =
        List.of("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:stone");
    CompoundTag written =
        LitematicWriter.write(
            metadata(), new LitematicWriter.Contents(2, 1, 1, palette, new int[] {1, 3}, Map.of()));

    CompoundTag region = written.compound("Regions").orElseThrow().compound("Edited").orElseThrow();
    assertEquals(2, region.compounds("BlockStatePalette").size());
    assertEquals(2, readBack(written).blockCount());
  }
}
