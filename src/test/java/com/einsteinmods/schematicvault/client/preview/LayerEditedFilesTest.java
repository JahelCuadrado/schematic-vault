package com.einsteinmods.schematicvault.client.preview;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.edit.LitematicWriter;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFixtures;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.nbt.NbtReader;
import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Rules through the real game blocks: what the 3D draws for each cell is what the saved file holds,
 * in every format, and the list shows the same.
 */
class LayerEditedFilesTest {
  private static final String OAK = "minecraft:oak_planks";
  private static final String SPRUCE = "minecraft:spruce_planks";
  private static final String STONE = "minecraft:stone";
  private static final String GLASS = "minecraft:glass";
  private static final String CHEST = "minecraft:chest";
  private static final String BARREL = "minecraft:barrel";
  private static final String AIR = "minecraft:air";
  private static final String STAIRS =
      "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]";
  private static final String MODDED = "create:brass_casing";
  private static final int DATA_VERSION = 4325;

  /** Five layers of two cells: oak and stairs, stone, chest, the mod's block and oak. */
  private static final List<String> PALETTE = List.of(AIR, OAK, STAIRS, STONE, CHEST, MODDED);

  private static final int[] CELLS = {1, 2, 3, 1, 4, 1, 5, 1, 1, 4};
  private static final int SIZE_X = 2;
  private static final int SIZE_Y = 5;

  private static final BlockRule OAK_LOW = rule(OAK, SPRUCE, 1, 2);
  private static final BlockRule OAK_HIGH = rule(OAK, GLASS, 3, 4);

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static BlockRule rule(String source, String target, int bottom, int top) {
    return new BlockRule(source, target, new LayerRange(bottom, top));
  }

  private static BlockReplacements rules(BlockRule... rules) {
    BlockReplacements replacements = BlockReplacements.NONE;
    for (BlockRule rule : rules) {
      replacements = replacements.with(rule);
    }
    return replacements;
  }

  private static Map<Integer, CompoundTag> chestData() {
    return Map.of(4, compound("id", string(CHEST)), 9, compound("id", string(CHEST)));
  }

  private static CompoundTag litematic() {
    return LitematicWriter.write(
        new LitematicWriter.Metadata("Test", "", "", 0, DATA_VERSION),
        new LitematicWriter.Contents(SIZE_X, SIZE_Y, 1, PALETTE, CELLS, chestData()));
  }

  private static CompoundTag sponge() {
    Map<String, NbtTag> palette = new LinkedHashMap<>();
    for (int index = 0; index < PALETTE.size(); index++) {
      palette.put(PALETTE.get(index), integer(index));
    }
    byte[] data = new byte[CELLS.length];
    for (int index = 0; index < CELLS.length; index++) {
      data[index] = (byte) CELLS[index];
    }
    return compound(
        "Version", integer(2),
        "DataVersion", integer(DATA_VERSION),
        "Width", new ShortTag((short) SIZE_X),
        "Height", new ShortTag((short) SIZE_Y),
        "Length", new ShortTag((short) 1),
        "PaletteMax", integer(PALETTE.size()),
        "Palette", new CompoundTag(palette),
        "BlockData", new ByteArrayTag(data),
        "BlockEntities",
            list(
                compound("Pos", new IntArrayTag(new int[] {0, 2, 0}), "Id", string(CHEST)),
                compound("Pos", new IntArrayTag(new int[] {1, 4, 0}), "Id", string(CHEST))));
  }

  private static CompoundTag structure() {
    ListTag palette =
        new ListTag(PALETTE.stream().<NbtTag>map(BlockStateString::compound).toList());
    NbtTag[] blocks = new NbtTag[CELLS.length];
    for (int index = 0; index < CELLS.length; index++) {
      CompoundTag block =
          compound(
              "pos",
              list(integer(index % SIZE_X), integer(index / SIZE_X), integer(0)),
              "state",
              integer(CELLS[index]));
      blocks[index] = CELLS[index] == 4 ? block.with("nbt", compound("id", string(CHEST))) : block;
    }
    return compound(
        "DataVersion", integer(DATA_VERSION),
        "size", list(integer(SIZE_X), integer(SIZE_Y), integer(1)),
        "palette", palette,
        "blocks", list(blocks));
  }

  private static VoxelVolume read(SchematicFormat format, byte[] bytes)
      throws VolumeFormatException {
    return SchematicReaders.read(format, bytes, LegacyBlocks::load);
  }

  private static LoadedFile load(SchematicFormat format, CompoundTag root)
      throws VolumeFormatException, NbtFormatException {
    byte[] bytes = NbtFixtures.gzip(root);
    return LoadedFile.of(read(format, bytes), bytes, format);
  }

  /** The state the 3D draws for a cell. */
  private static BlockState drawn(LoadedFile file, LoadedFile.EditedPalette edited, int cell) {
    VoxelVolume volume = file.volume();
    int band = edited.bandOfY()[cell / (volume.sizeX() * volume.sizeZ())];
    return edited.palettes()[band].states()[volume.cells()[cell]];
  }

  /** Saves {@code replacements} and checks each saved cell against what the 3D draws. */
  private static VoxelVolume saved(
      SchematicFormat format, CompoundTag root, BlockReplacements replacements)
      throws VolumeFormatException, NbtFormatException {
    LoadedFile file = load(format, root);
    LoadedFile.EditedPalette edited = file.edited(replacements);
    EditedFile written = EditedFiles.write(file, edited, format, "Saved", "", 0);
    VoxelVolume back = read(written.format(), written.bytes());
    PreviewPalette resolved = PreviewPalette.resolve(back);
    assertEquals(file.volume().volume(), back.volume());
    for (int cell = 0; cell < back.volume(); cell++) {
      assertEquals(
          drawn(file, edited, cell), resolved.states()[back.cells()[cell]], "cell " + cell);
    }
    return back;
  }

  private static String stateAt(VoxelVolume volume, int x, int y) {
    return volume.palette().get(volume.cells()[volume.index(x, y, 0)]);
  }

  @Test
  void theSavedFileHoldsWhatThe3dDraws() throws VolumeFormatException, NbtFormatException {
    BlockReplacements replacements =
        rules(OAK_LOW, OAK_HIGH, rule(STONE, AIR, 1, 1), rule(CHEST, BARREL, 4, 4));

    for (VoxelVolume back :
        List.of(
            saved(SchematicFormat.LITEMATIC, litematic(), replacements),
            saved(SchematicFormat.SCHEM, sponge(), replacements),
            saved(SchematicFormat.NBT, structure(), replacements))) {
      assertEquals(OAK, stateAt(back, 0, 0), "under the range");
      assertEquals(SPRUCE, stateAt(back, 1, 1));
      assertEquals(SPRUCE, stateAt(back, 1, 2));
      assertEquals(GLASS, stateAt(back, 1, 3));
      assertEquals(AIR, stateAt(back, 0, 1));
      assertEquals(STAIRS, stateAt(back, 1, 0), "an unedited state keeps its text");
      assertEquals(MODDED, stateAt(back, 0, 3), "a mod's block keeps its id");
      assertEquals(CHEST, stateAt(back, 0, 2));
      assertNotNull(back.blockEntities().get(back.index(0, 2, 0)), "this chest stays one");
      assertNull(back.blockEntities().get(back.index(1, 4, 0)), "this one became a barrel");
    }
  }

  @Test
  void aLegacyFileIsConvertedWithItsBands() throws VolumeFormatException, NbtFormatException {
    // Two layers of stone and glass (ids 1 and 20): only the top stone changes.
    CompoundTag mcedit =
        compound(
            "Width", new ShortTag((short) 2),
            "Height", new ShortTag((short) 2),
            "Length", new ShortTag((short) 1),
            "Blocks", new ByteArrayTag(new byte[] {1, 20, 1, 20}),
            "Data", new ByteArrayTag(new byte[4]));

    VoxelVolume back = saved(SchematicFormat.SCHEMATIC, mcedit, rules(rule(STONE, SPRUCE, 1, 1)));

    assertEquals(STONE, stateAt(back, 0, 0));
    assertEquals(SPRUCE, stateAt(back, 0, 1));
    assertEquals(GLASS, stateAt(back, 1, 1));
  }

  @Test
  void aWholeDesignRuleKeepsThePaletteOnlyPath() throws VolumeFormatException, NbtFormatException {
    BlockRule everywhere = new BlockRule(OAK, SPRUCE, LayerRange.ALL);
    LoadedFile file = load(SchematicFormat.LITEMATIC, litematic());
    LoadedFile.EditedPalette edited = file.edited(rules(everywhere));

    EditedFile written = EditedFiles.write(file, edited, SchematicFormat.LITEMATIC, "", "", 0);
    CompoundTag region =
        NbtReader.read(written.bytes())
            .compound("Regions")
            .orElseThrow()
            .compound("Test")
            .orElseThrow();

    assertTrue(edited.bands().uniform());
    assertFalse(
        region.compounds("BlockStatePalette").stream()
            .map(BlockStateString::of)
            .toList()
            .contains(OAK),
        "the entry changed in place, none was added");
    saved(SchematicFormat.LITEMATIC, litematic(), rules(everywhere));
  }

  @Test
  void theListShowsTheNewBlockOnItsLayersAndTheOldElsewhere()
      throws VolumeFormatException, NbtFormatException {
    LoadedFile file = load(SchematicFormat.LITEMATIC, litematic());

    BlockList list = file.blockList(file.edited(rules(OAK_LOW)));

    BlockList.Row kept = row(list, OAK, null);
    BlockList.Row changed = row(list, OAK, OAK_LOW);
    assertEquals(3, kept.count());
    assertEquals(2, changed.count());
    assertSame(Blocks.SPRUCE_PLANKS, changed.replacement());
    assertTrue(list.edited());
    assertEquals(10, list.total());
  }

  @Test
  void aRuleWithNoCellsInTheRangeIsStillListedToUndo()
      throws VolumeFormatException, NbtFormatException {
    LoadedFile file = load(SchematicFormat.LITEMATIC, litematic());
    LoadedFile.EditedPalette edited = file.edited(rules(OAK_LOW, OAK_HIGH));

    BlockList list = file.blockList(edited, new LayerRange(0, 0));

    assertEquals(
        List.of(OAK_LOW, OAK_HIGH), list.otherRules().stream().map(r -> r.rule()).toList());
    assertEquals(0, list.otherRules().getFirst().count());
    assertEquals(2, list.total(), "the bottom layer's oak and stairs");
  }

  @Test
  void undoingOneRuleLeavesTheOther() throws VolumeFormatException, NbtFormatException {
    BlockReplacements both = rules(OAK_LOW, OAK_HIGH);

    VoxelVolume back = saved(SchematicFormat.NBT, structure(), both.without(OAK_LOW));

    assertEquals(OAK, stateAt(back, 1, 1));
    assertEquals(GLASS, stateAt(back, 1, 3));
  }

  @Test
  void removedCellsLeaveTheTotalAndTypesFollowTheResult()
      throws VolumeFormatException, NbtFormatException {
    LoadedFile file = load(SchematicFormat.LITEMATIC, litematic());

    BlockList list = file.blockList(file.edited(rules(rule(OAK, AIR, 0, 4))));

    assertEquals(5, list.total());
    assertEquals(4, list.types(), "stairs, stone, chest, the mod's block");
  }

  @Test
  void onlyTheRuleThatReachesAChestWarnsOfLostContents()
      throws VolumeFormatException, NbtFormatException {
    BlockRule reaches = rule(CHEST, BARREL, 4, 4);
    BlockRule misses = rule(CHEST, GLASS, 0, 1);
    LoadedFile file = load(SchematicFormat.LITEMATIC, litematic());

    BlockList list = file.blockList(file.edited(rules(reaches, misses)));

    assertTrue(row(list, CHEST, reaches).dropsData());
    assertFalse(
        list.otherRules().stream()
            .filter(row -> row.rule() == misses)
            .findFirst()
            .orElseThrow()
            .dropsData());
  }

  private static BlockList.Row row(BlockList list, String blockId, BlockRule rule) {
    return list.rows().stream()
        .filter(row -> row.blockId().equals(blockId) && row.rule() == rule)
        .findFirst()
        .orElseThrow();
  }
}
