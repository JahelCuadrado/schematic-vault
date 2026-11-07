package com.einsteinmods.schematicvault.core.edit;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchematicRewriterTest {
  private static final String STAIRS = "minecraft:oak_stairs[facing=east,half=top,shape=straight]";
  private static final String SPRUCE_STAIRS =
      "minecraft:spruce_stairs[facing=east,half=top,shape=straight]";

  /** Planks merge into the spruce planks already there; stone goes; the chest's type changes. */
  private static final Map<String, StateChange> CHANGES =
      Map.of(
          "minecraft:oak_planks",
          new StateChange("minecraft:spruce_planks", true),
          "minecraft:stone",
          new StateChange("minecraft:air", true),
          STAIRS,
          new StateChange(SPRUCE_STAIRS, true),
          "minecraft:chest[facing=north]",
          new StateChange("minecraft:barrel[facing=up]", false),
          "minecraft:oak_sign[rotation=4]",
          new StateChange("minecraft:spruce_sign[rotation=4]", true));

  /** Nine entries (4 bits a cell); seven after the merges (3 bits). */
  private static final List<String> LITEMATIC_PALETTE =
      List.of(
          "minecraft:air",
          "minecraft:stone",
          STAIRS,
          "minecraft:chest[facing=north]",
          "minecraft:oak_planks",
          "minecraft:spruce_planks",
          "minecraft:oak_sign[rotation=4]",
          "minecraft:glass",
          "minecraft:dirt");

  // 4 x 2 x 3 cells: index = (y * 3 + z) * 4 + x.
  private static final int SIZE_X = 4;
  private static final int SIZE_Y = 2;
  private static final int SIZE_Z = 3;
  private static final int CELLS = SIZE_X * SIZE_Y * SIZE_Z;

  /** The chest sits in cell 3 (x 3), the sign in cell 6 (x 2, z 1). */
  private static int[] litematicCells() {
    int[] cells = new int[CELLS];
    for (int index = 0; index < CELLS; index++) {
      cells[index] = index % LITEMATIC_PALETTE.size();
    }
    return cells;
  }

  private static ListTag paletteTag(List<String> states) {
    return new ListTag(states.stream().<NbtTag>map(BlockStateString::compound).toList());
  }

  private static CompoundTag litematic() {
    CompoundTag region =
        compound(
            "Position", compound("x", integer(0), "y", integer(0), "z", integer(0)),
            "Size", compound("x", integer(SIZE_X), "y", integer(-SIZE_Y), "z", integer(SIZE_Z)),
            "BlockStatePalette", paletteTag(LITEMATIC_PALETTE),
            "BlockStates",
                new LongArrayTag(PackedStates.pack(litematicCells(), LITEMATIC_PALETTE.size())),
            "TileEntities",
                list(
                    compound(
                        "x",
                        integer(3),
                        "y",
                        integer(0),
                        "z",
                        integer(0),
                        "id",
                        string("minecraft:chest")),
                    compound(
                        "x",
                        integer(2),
                        "y",
                        integer(0),
                        "z",
                        integer(1),
                        "id",
                        string("minecraft:sign"))),
            "Entities", list(compound("id", string("minecraft:cow"))));
    return compound(
        "Version", integer(6),
        "MinecraftDataVersion", integer(4325),
        "Metadata", compound("Name", string("Test"), "TotalBlocks", integer(999)),
        "Regions", compound("main", region));
  }

  private static VoxelVolume read(SchematicFormat format, CompoundTag root)
      throws VolumeFormatException {
    return SchematicReaders.read(format, NbtFixtures.gzip(root), LegacyBlocks::load);
  }

  /** The edited file, read back, holds each old cell's new state; air stays air. */
  private static VoxelVolume assertRoundTrip(
      SchematicFormat format, CompoundTag root, Map<String, StateChange> changes)
      throws VolumeFormatException {
    VoxelVolume before = read(format, root);
    CompoundTag edited = SchematicRewriter.rewrite(format, root, changes::get);
    VoxelVolume after = read(format, edited);
    long removed = 0;
    for (int index = 0; index < before.volume(); index++) {
      String old = before.palette().get(before.cells()[index]);
      StateChange change = changes.get(old);
      String expected = change == null ? old : change.state();
      if (BlockStateString.isAir(expected)) {
        expected = VoxelVolume.AIR_STATE;
        removed += BlockStateString.isAir(old) ? 0 : 1;
      }
      assertEquals(expected, after.palette().get(after.cells()[index]), "cell " + index);
    }
    assertEquals(before.blockCount() - removed, after.blockCount());
    return after;
  }

  @Test
  void litematicMergesEntriesRepacksAndCountsBlocksAgain() throws VolumeFormatException {
    CompoundTag edited =
        SchematicRewriter.rewrite(SchematicFormat.LITEMATIC, litematic(), CHANGES::get);
    VoxelVolume after = assertRoundTrip(SchematicFormat.LITEMATIC, litematic(), CHANGES);

    CompoundTag region = edited.compound("Regions").orElseThrow().compound("main").orElseThrow();
    // air (stone merged in), spruce stairs, barrel, spruce planks (oak merged in), sign, glass,
    // dirt.
    assertEquals(7, region.compounds("BlockStatePalette").size());
    assertEquals(PackedStates.longs(CELLS, 3), region.longArray("BlockStates").length);
    assertEquals(
        after.blockCount(), edited.compound("Metadata").orElseThrow().intValue("TotalBlocks", -1));
    assertEquals("Test", edited.compound("Metadata").orElseThrow().string("Name").orElseThrow());
    assertEquals(1, region.compounds("Entities").size(), "entities are kept");
    assertEquals(4325, edited.intValue("MinecraftDataVersion", 0));
  }

  @Test
  void blockEntityDataIsDroppedOnlyWhenTheTypeChanges() throws VolumeFormatException {
    VoxelVolume after = assertRoundTrip(SchematicFormat.LITEMATIC, litematic(), CHANGES);

    assertNull(after.blockEntities().get(after.index(3, 0, 0)), "chest data dropped (barrel)");
    assertNotNull(after.blockEntities().get(after.index(2, 0, 1)), "sign data kept (sign)");
  }

  @Test
  void nothingToChangeGivesTheSameTree() throws VolumeFormatException {
    CompoundTag sponge = sponge(2);
    assertSame(sponge, SchematicRewriter.rewrite(SchematicFormat.SCHEM, sponge, state -> null));
  }

  /** air 0, stone 1, oak planks 2, spruce planks 3, chest 4; cell i holds i % 5. */
  private static CompoundTag sponge(int version) {
    Map<String, NbtTag> palette = new LinkedHashMap<>();
    List<String> states =
        List.of(
            "minecraft:air",
            "minecraft:stone",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:chest[facing=north]");
    for (int index = 0; index < states.size(); index++) {
      palette.put(states.get(index), integer(index));
    }
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (int cell = 0; cell < CELLS; cell++) {
      data.write(cell % states.size());
    }
    // Cell 4 (x 0, z 1) holds a chest; cell 9 (x 1, z 2) another.
    ListTag blockEntities =
        list(
            compound("Pos", new IntArrayTag(new int[] {0, 0, 1}), "Id", string("minecraft:chest")),
            compound("Pos", new IntArrayTag(new int[] {1, 0, 2}), "Id", string("minecraft:chest")));
    CompoundTag dimensions =
        compound(
            "Version", integer(version),
            "DataVersion", integer(3953),
            "Width", new ShortTag((short) SIZE_X),
            "Height", new ShortTag((short) SIZE_Y),
            "Length", new ShortTag((short) SIZE_Z));
    if (version >= 3) {
      return compound(
          "Schematic",
          dimensions.with(
              "Blocks",
              compound(
                  "Palette", new CompoundTag(palette),
                  "Data", new ByteArrayTag(data.toByteArray()),
                  "BlockEntities", blockEntities)));
    }
    return dimensions
        .with("PaletteMax", integer(states.size()))
        .with("Palette", new CompoundTag(palette))
        .with("BlockData", new ByteArrayTag(data.toByteArray()))
        .with("BlockEntities", blockEntities);
  }

  @Test
  void spongeVersionTwoMergesThePaletteAndDropsChangedBlockEntities() throws VolumeFormatException {
    VoxelVolume after = assertRoundTrip(SchematicFormat.SCHEM, sponge(2), CHANGES);
    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.SCHEM, sponge(2), CHANGES::get);

    // air (stone merged in), spruce planks (oak merged in), barrel.
    assertEquals(3, edited.compound("Palette").orElseThrow().values().size());
    assertEquals(3, edited.intValue("PaletteMax", 0));
    assertTrue(after.blockEntities().isEmpty());
  }

  @Test
  void spongeVersionThreeKeepsItsNesting() throws VolumeFormatException {
    Map<String, StateChange> planksOnly =
        Map.of("minecraft:oak_planks", new StateChange("minecraft:birch_planks", true));
    VoxelVolume after = assertRoundTrip(SchematicFormat.SCHEM, sponge(3), planksOnly);
    CompoundTag edited =
        SchematicRewriter.rewrite(SchematicFormat.SCHEM, sponge(3), planksOnly::get);

    CompoundTag blocks =
        edited.compound("Schematic").orElseThrow().compound("Blocks").orElseThrow();
    assertEquals(5, blocks.compound("Palette").orElseThrow().values().size());
    assertEquals(2, after.blockEntities().size(), "chests untouched keep their data");
  }

  private static CompoundTag structureBlock(int x, int y, int z, int state, boolean nbt) {
    CompoundTag block =
        compound("pos", list(integer(x), integer(y), integer(z)), "state", integer(state));
    return nbt ? block.with("nbt", compound("id", string("minecraft:chest"))) : block;
  }

  private static CompoundTag structure(NbtTag palettes, boolean several) {
    return compound(
        "DataVersion",
        integer(3953),
        "size",
        list(integer(3), integer(2), integer(1)),
        several ? "palettes" : "palette",
        palettes,
        "blocks",
        list(
            structureBlock(0, 0, 0, 0, false),
            structureBlock(1, 0, 0, 1, false),
            structureBlock(2, 0, 0, 2, false),
            structureBlock(0, 1, 0, 3, true),
            structureBlock(1, 1, 0, 4, false)));
  }

  private static final List<String> STRUCTURE_PALETTE =
      List.of(
          "minecraft:stone",
          "minecraft:oak_planks",
          "minecraft:spruce_planks",
          "minecraft:chest[facing=north]",
          "minecraft:air");

  @Test
  void structureReindexesBlocksAndDropsTheirNbtOnATypeChange() throws VolumeFormatException {
    CompoundTag root = structure(paletteTag(STRUCTURE_PALETTE), false);
    VoxelVolume after = assertRoundTrip(SchematicFormat.NBT, root, CHANGES);
    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.NBT, root, CHANGES::get);

    // air (stone merged into the listed air), spruce planks (oak merged in), barrel.
    assertEquals(3, edited.compounds("palette").size());
    assertEquals(5, edited.compounds("blocks").size(), "blocks replaced by air stay listed");
    assertFalse(edited.compounds("blocks").get(3).contains("nbt"));
    assertTrue(after.blockEntities().isEmpty());
  }

  @Test
  void severalPalettesMergeOnlyEntriesEqualInEveryOne() throws VolumeFormatException {
    List<String> variant =
        List.of(
            "minecraft:andesite",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:chest[facing=north]",
            "minecraft:air");
    CompoundTag root = structure(list(paletteTag(STRUCTURE_PALETTE), paletteTag(variant)), true);
    Map<String, StateChange> planksOnly =
        Map.of("minecraft:oak_planks", new StateChange("minecraft:spruce_planks", true));
    assertRoundTrip(SchematicFormat.NBT, root, planksOnly);
    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.NBT, root, planksOnly::get);

    List<NbtTag> palettes = edited.list("palettes");
    assertEquals(2, palettes.size());
    // Oak and spruce planks are equal in the first palette, not in the second: no merge.
    for (NbtTag palette : palettes) {
      assertEquals(5, ((ListTag) palette).values().size());
    }
  }

  @Test
  void aLegacyMcEditFileIsNeverRewritten() {
    CompoundTag mcedit =
        compound(
            "Width", new ShortTag((short) 1),
            "Height", new ShortTag((short) 1),
            "Length", new ShortTag((short) 1),
            "Blocks", new ByteArrayTag(new byte[] {1}),
            "Data", new ByteArrayTag(new byte[] {0}));
    assertEquals(
        SchematicFormat.LITEMATIC,
        SchematicRewriter.savedFormat(SchematicFormat.SCHEMATIC, mcedit));
    assertThrows(
        VolumeFormatException.class,
        () -> SchematicRewriter.rewrite(SchematicFormat.SCHEMATIC, mcedit, CHANGES::get));
    assertEquals(
        SchematicFormat.SCHEM, SchematicRewriter.savedFormat(SchematicFormat.SCHEMATIC, sponge(2)));
  }
}
