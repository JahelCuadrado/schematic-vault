package com.einsteinmods.schematicvault.core.volume;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import org.junit.jupiter.api.Test;

class StructureReaderTest {
  private static final NbtTag PALETTE =
      list(
          compound("Name", string("minecraft:stone")),
          compound("Name", string("minecraft:air")),
          compound(
              "Name",
              string("minecraft:oak_sign"),
              "Properties",
              compound("rotation", string("4"), "waterlogged", string("false"))));

  private static CompoundTag block(int x, int y, int z, int state) {
    return compound("pos", list(integer(x), integer(y), integer(z)), "state", integer(state));
  }

  @Test
  void readsTheFirstOfSeveralPalettesAndKeepsBlockEntityNbt() throws VolumeFormatException {
    CompoundTag sign =
        compound(
            "pos", list(integer(1), integer(1), integer(0)),
            "state", integer(2),
            "nbt", compound("id", string("minecraft:sign")));
    CompoundTag root =
        compound(
            "DataVersion", integer(3465),
            "size", list(integer(2), integer(2), integer(1)),
            "palettes", list(PALETTE, PALETTE),
            "blocks", list(block(0, 0, 0, 0), block(1, 0, 0, 1), sign));
    VoxelVolume volume =
        SchematicReaders.read(SchematicFormat.NBT, NbtFixtures.gzip(root), LegacyBlocks::load);

    assertEquals(2, volume.blockCount());
    assertEquals("minecraft:stone", volume.palette().get(volume.cells()[volume.index(0, 0, 0)]));
    assertEquals(
        "minecraft:oak_sign[rotation=4,waterlogged=false]",
        volume.palette().get(volume.cells()[volume.index(1, 1, 0)]));
    assertEquals(VoxelVolume.AIR, volume.cells()[volume.index(1, 0, 0)]);
    assertEquals(1, volume.blockEntities().size());
    assertEquals(3465, volume.dataVersion());
  }

  @Test
  void refusesAStateOutsideThePalette() {
    CompoundTag root =
        compound(
            "size", list(integer(1), integer(1), integer(1)),
            "palette", PALETTE,
            "blocks", list(block(0, 0, 0, 7)));
    assertThrows(
        VolumeFormatException.class,
        () ->
            SchematicReaders.read(SchematicFormat.NBT, NbtFixtures.gzip(root), LegacyBlocks::load));
  }
}
