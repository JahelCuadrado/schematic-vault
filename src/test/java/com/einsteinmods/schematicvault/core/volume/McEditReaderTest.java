package com.einsteinmods.schematicvault.core.volume;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class McEditReaderTest {
  @Test
  void mapsLegacyIdsThroughTheShippedTableWithItsFallbacks()
      throws VolumeFormatException, IOException {
    // 4 x 1 x 1: stone, granite (1:1), oak stairs facing east (53:0), an unknown id past 255.
    byte[] ids = {1, 1, 53, (byte) 0xF4};
    byte[] data = {0, 1, 0, 0};
    // AddBlocks: cell 3 is odd, so its extra bits are the high nibble of byte 1 (id 0x1F4 = 500).
    byte[] add = {0, 0x10};
    CompoundTag root =
        compound(
            "Width", new ShortTag((short) 4),
            "Height", new ShortTag((short) 1),
            "Length", new ShortTag((short) 1),
            "Materials", string("Alpha"),
            "Blocks", new ByteArrayTag(ids),
            "Data", new ByteArrayTag(data),
            "AddBlocks", new ByteArrayTag(add),
            "TileEntities",
                list(
                    compound(
                        "id", string("Chest"), "x", integer(0), "y", integer(0), "z", integer(0))));
    VoxelVolume volume =
        SchematicReaders.read(
            SchematicFormat.SCHEMATIC, NbtFixtures.gzip(root), LegacyBlocks::load);

    LegacyBlocks legacy = LegacyBlocks.load();
    assertEquals(4, volume.blockCount());
    assertEquals("minecraft:stone", volume.palette().get(volume.cells()[0]));
    assertEquals("minecraft:granite", volume.palette().get(volume.cells()[1]));
    assertEquals(legacy.state(53, 0), volume.palette().get(volume.cells()[2]));
    assertEquals("legacy:500", volume.palette().get(volume.cells()[3]));
    assertEquals(1519, volume.dataVersion());
    assertEquals(1343, volume.blockEntityDataVersion());
    assertEquals(1, volume.blockEntities().size());
  }

  @Test
  void fallsBackToDataZeroThenToAnyStateOfTheId() throws IOException {
    LegacyBlocks legacy =
        LegacyBlocks.parse(
            new StringReader(
                "{\"blocks\": {\"5:0\": \"minecraft:oak_planks\", \"54:2\": \"minecraft:chest[facing=north]\","
                    + " \"54:3\": \"minecraft:chest[facing=south]\"}}"));
    assertEquals("minecraft:oak_planks", legacy.state(5, 9));
    assertEquals("minecraft:chest[facing=north]", legacy.state(54, 7));
    assertEquals("legacy:1000", legacy.state(1000, 0));
  }
}
