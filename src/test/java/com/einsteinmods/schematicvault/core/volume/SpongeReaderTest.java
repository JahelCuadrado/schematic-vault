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
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpongeReaderTest {
  /** 200 palette entries: indices past 127 take two varint bytes. */
  private static final int PALETTE_SIZE = 200;

  private static final int WIDTH = 5;
  private static final int HEIGHT = 4;
  private static final int LENGTH = 10;

  private static CompoundTag palette() {
    Map<String, NbtTag> entries = new LinkedHashMap<>();
    entries.put("minecraft:air", integer(0));
    for (int index = 1; index < PALETTE_SIZE; index++) {
      entries.put("minecraft:block_" + index, integer(index));
    }
    return new CompoundTag(entries);
  }

  /** Cell i holds palette index i % 200 (index 0 is air). */
  private static byte[] varints() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int cell = 0; cell < WIDTH * HEIGHT * LENGTH; cell++) {
      int value = cell % PALETTE_SIZE;
      while ((value & ~0x7F) != 0) {
        bytes.write(value & 0x7F | 0x80);
        value >>>= 7;
      }
      bytes.write(value);
    }
    return bytes.toByteArray();
  }

  private static long expectedBlocks() {
    long blocks = 0;
    for (int cell = 0; cell < WIDTH * HEIGHT * LENGTH; cell++) {
      if (cell % PALETTE_SIZE != 0) {
        blocks++;
      }
    }
    return blocks;
  }

  private static NbtTag dimensions(String key, int value) {
    return new ShortTag((short) value);
  }

  @Test
  void readsVersionTwo() throws VolumeFormatException {
    CompoundTag root =
        compound(
            "Version", integer(2),
            "DataVersion", integer(2586),
            "Width", dimensions("Width", WIDTH),
            "Height", dimensions("Height", HEIGHT),
            "Length", dimensions("Length", LENGTH),
            "Palette", palette(),
            "BlockData", new ByteArrayTag(varints()),
            "BlockEntities",
                list(
                    compound(
                        "Pos", new IntArrayTag(new int[] {1, 0, 0}),
                        "Id", string("minecraft:sign"),
                        "Text1", string("hello"))));
    VoxelVolume volume =
        SchematicReaders.read(SchematicFormat.SCHEM, NbtFixtures.gzip(root), LegacyBlocks::load);

    assertEquals(expectedBlocks(), volume.blockCount());
    assertEquals(2586, volume.dataVersion());
    // Cell 150: x 0, z 0, y 3 (150 = (3 * 10 + 0) * 5 + 0).
    assertEquals(
        "minecraft:block_150", volume.palette().get(volume.cells()[volume.index(0, 3, 0)]));
    CompoundTag sign = volume.blockEntities().get(volume.index(1, 0, 0));
    assertEquals("minecraft:sign", sign.string("id").orElseThrow());
    assertEquals("hello", sign.string("Text1").orElseThrow());
  }

  @Test
  void readsVersionThreeNestedUnderSchematic() throws VolumeFormatException {
    CompoundTag schematic =
        compound(
            "Version", integer(3),
            "DataVersion", integer(3953),
            "Width", dimensions("Width", WIDTH),
            "Height", dimensions("Height", HEIGHT),
            "Length", dimensions("Length", LENGTH),
            "Blocks",
                compound(
                    "Palette", palette(),
                    "Data", new ByteArrayTag(varints()),
                    "BlockEntities",
                        list(
                            compound(
                                "Pos", new IntArrayTag(new int[] {2, 1, 3}),
                                "Id", string("minecraft:chest"),
                                "Data", compound("CustomName", string("loot"))))));
    VoxelVolume volume =
        SchematicReaders.read(
            SchematicFormat.SCHEM,
            NbtFixtures.gzip(compound("Schematic", schematic)),
            LegacyBlocks::load);

    assertEquals(expectedBlocks(), volume.blockCount());
    CompoundTag chest = volume.blockEntities().get(volume.index(2, 1, 3));
    assertEquals("minecraft:chest", chest.string("id").orElseThrow());
    assertEquals("loot", chest.string("CustomName").orElseThrow());
  }

  @Test
  void refusesDataShorterThanTheBox() {
    CompoundTag root =
        compound(
            "Version", integer(2),
            "Width", dimensions("Width", WIDTH),
            "Height", dimensions("Height", HEIGHT),
            "Length", dimensions("Length", LENGTH),
            "Palette", palette(),
            "BlockData", new ByteArrayTag(new byte[3]));
    assertThrows(
        VolumeFormatException.class,
        () ->
            SchematicReaders.read(
                SchematicFormat.SCHEM, NbtFixtures.gzip(root), LegacyBlocks::load));
  }
}
