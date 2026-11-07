package com.einsteinmods.schematicvault.core.edit;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Edits limited to some layers, in every format: read, apply rules, write, read again and compare
 * cell by cell with the volume the rules describe.
 */
class LayerRewriteTest {
  private static final String AIR = "minecraft:air";
  private static final String OAK = "minecraft:oak_planks";
  private static final String SPRUCE = "minecraft:spruce_planks";
  private static final String STONE = "minecraft:stone";
  private static final String GLASS = "minecraft:glass";
  private static final String DIRT = "minecraft:dirt";
  private static final String CHEST = "minecraft:chest";
  private static final String BARREL = "minecraft:barrel";
  private static final String OAK_SIGN = "minecraft:oak_sign";
  private static final String SPRUCE_SIGN = "minecraft:spruce_sign";
  private static final String MODDED = "create:brass_casing[facing=north]";

  private static final Set<String> BLOCK_ENTITY_TYPES_CHANGE = Set.of(CHEST);

  private static BlockRule rule(String source, String target, int bottom, int top) {
    return new BlockRule(source, target, new LayerRange(bottom, top));
  }

  /** Changes by block name, as the client does; only a chest's data goes with its type. */
  private static LayerChanges changes(int height, BlockRule... rules) {
    RuleBands bands = RuleBands.of(List.of(rules), height);
    return new LayerChanges(
        bands.bandOfY(),
        bands.count(),
        (band, state) -> {
          String name = BlockStateString.parse(state).name();
          BlockRule rule = bands.rule(band, name);
          return rule == null || rule.keepsBlock()
              ? null
              : new StateChange(rule.targetBlock(), !BLOCK_ENTITY_TYPES_CHANGE.contains(name));
        });
  }

  private static VoxelVolume read(SchematicFormat format, CompoundTag root)
      throws VolumeFormatException {
    return SchematicReaders.read(format, NbtFixtures.gzip(root), LegacyBlocks::load);
  }

  private static String stateAt(VoxelVolume volume, int cell) {
    return volume.palette().get(volume.cells()[cell]);
  }

  /** Every cell of {@code after} holds what {@code changes} make of {@code before}'s cell. */
  static void assertCells(VoxelVolume before, VoxelVolume after, LayerChanges changes) {
    assertEquals(before.sizeX(), after.sizeX());
    assertEquals(before.sizeY(), after.sizeY());
    assertEquals(before.sizeZ(), after.sizeZ());
    int layerCells = before.sizeX() * before.sizeZ();
    for (int cell = 0; cell < before.volume(); cell++) {
      String old = stateAt(before, cell);
      StateChange change =
          BlockStateString.isAir(old)
              ? null
              : changes.mapper().change(changes.bandOfY()[cell / layerCells], old);
      String expected = change == null ? old : change.state();
      assertEquals(
          BlockStateString.isAir(expected) ? VoxelVolume.AIR_STATE : expected,
          stateAt(after, cell),
          "cell " + cell);
    }
  }

  private static VoxelVolume roundTrip(
      SchematicFormat format, CompoundTag root, LayerChanges changes) throws VolumeFormatException {
    VoxelVolume before = read(format, root);
    VoxelVolume after = read(format, SchematicRewriter.rewrite(format, root, changes));
    assertCells(before, after, changes);
    return after;
  }

  private static ListTag paletteTag(List<String> states) {
    return new ListTag(states.stream().<NbtTag>map(BlockStateString::compound).toList());
  }

  private static CompoundTag position(int x, int y, int z) {
    return compound("x", integer(x), "y", integer(y), "z", integer(z));
  }

  private static CompoundTag region(
      int[] at, int[] size, List<String> palette, int[] cells, NbtTag... blockEntities) {
    return compound(
        "Position", position(at[0], at[1], at[2]),
        "Size", position(size[0], size[1], size[2]),
        "BlockStatePalette", paletteTag(palette),
        "BlockStates", new LongArrayTag(PackedStates.pack(cells, palette.size())),
        "TileEntities", list(blockEntities),
        "Entities", list(compound("id", string("minecraft:cow"))));
  }

  private static CompoundTag litematic(long totalBlocks, Object... namesAndRegions) {
    return compound(
        "Version", integer(6),
        "MinecraftDataVersion", integer(4325),
        "Metadata", compound("Name", string("Test"), "TotalBlocks", integer((int) totalBlocks)),
        "Regions", compound(namesAndRegions));
  }

  private static CompoundTag tile(int x, int y, int z, String id) {
    return compound("x", integer(x), "y", integer(y), "z", integer(z), "id", string(id));
  }

  /** A column of six layers: oak in every other, stone between; x 0 and x 1 mirror them. */
  private static CompoundTag column() {
    List<String> palette = List.of(AIR, OAK, STONE);
    int[] cells = {1, 1, 2, 2, 1, 0, 2, 2, 1, 1, 2, 0};
    return litematic(10, "main", region(new int[3], new int[] {2, 6, 1}, palette, cells));
  }

  @Test
  void aChangeInSomeLayersLeavesTheOthersAlone() throws VolumeFormatException {
    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, column(), changes(6, rule(OAK, SPRUCE, 2, 4)));

    assertEquals(OAK, stateAt(after, after.index(0, 0, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(0, 2, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(0, 4, 0)));
    assertEquals(AIR, stateAt(after, after.index(1, 2, 0)));
  }

  @Test
  void twoRulesOfOneBlockCoexistInTheFile() throws VolumeFormatException {
    VoxelVolume after =
        roundTrip(
            SchematicFormat.LITEMATIC,
            column(),
            changes(6, rule(OAK, SPRUCE, 0, 1), rule(OAK, GLASS, 4, 5)));

    assertEquals(SPRUCE, stateAt(after, after.index(0, 0, 0)));
    assertEquals(OAK, stateAt(after, after.index(0, 2, 0)));
    assertEquals(GLASS, stateAt(after, after.index(0, 4, 0)));
  }

  @Test
  void theFileKeepsItsPaletteAndCountsItsBlocksAgain() throws VolumeFormatException {
    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.LITEMATIC, column(), changes(6, rule(OAK, AIR, 2, 3)));

    CompoundTag region = edited.compound("Regions").orElseThrow().compound("main").orElseThrow();
    assertEquals(3, region.compounds("BlockStatePalette").size(), "air was already there");
    assertEquals(9, edited.compound("Metadata").orElseThrow().intValue("TotalBlocks", -1));
    assertEquals(1, region.compounds("Entities").size(), "entities are kept");
    assertEquals(9, read(SchematicFormat.LITEMATIC, edited).blockCount());
  }

  @Test
  void aRangeWithNothingToChangeKeepsTheSameTree() throws VolumeFormatException {
    CompoundTag root = column();
    assertSame(
        root,
        SchematicRewriter.rewrite(
            SchematicFormat.LITEMATIC, root, changes(6, rule(GLASS, DIRT, 0, 1))));
  }

  @Test
  void aNegativeSizeRegionAndARegionAboveCountFromTheDesignsBottom() throws VolumeFormatException {
    List<String> palette = List.of(AIR, OAK);
    CompoundTag low =
        region(new int[3], new int[] {2, 3, 1}, palette, new int[] {1, 1, 1, 1, 1, 1});
    // Reaches from y 7 down to 5, then the design's layers 5 to 7; two along x, stored from y 5.
    CompoundTag high =
        region(new int[] {4, 7, 0}, new int[] {-2, -3, 1}, palette, new int[] {1, 1, 1, 1, 1, 1});
    CompoundTag root = litematic(12, "low", low, "high", high);

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(8, rule(OAK, SPRUCE, 2, 5)));

    assertEquals(OAK, stateAt(after, after.index(0, 1, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(0, 2, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(3, 5, 0)));
    assertEquals(OAK, stateAt(after, after.index(3, 6, 0)));
  }

  @Test
  void regionsBelowZeroAreLayersOfTheDesignToo() throws VolumeFormatException {
    List<String> palette = List.of(AIR, OAK);
    CompoundTag low = region(new int[] {0, -4, 0}, new int[] {1, 2, 1}, palette, new int[] {1, 1});
    CompoundTag high = region(new int[] {0, 0, 0}, new int[] {1, 2, 1}, palette, new int[] {1, 1});
    CompoundTag root = litematic(4, "low", low, "high", high);

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(6, rule(OAK, SPRUCE, 1, 4)));

    assertEquals(OAK, stateAt(after, after.index(0, 0, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(0, 1, 0)));
    assertEquals(SPRUCE, stateAt(after, after.index(0, 4, 0)));
    assertEquals(OAK, stateAt(after, after.index(0, 5, 0)));
  }

  @Test
  void eachRegionHasItsOwnPaletteIndices() throws VolumeFormatException {
    CompoundTag first =
        region(new int[3], new int[] {2, 2, 1}, List.of(AIR, OAK, STONE), new int[] {1, 2, 1, 2});
    CompoundTag second =
        region(
            new int[] {3, 0, 0}, new int[] {2, 2, 1}, List.of(STONE, OAK), new int[] {0, 1, 1, 0});
    CompoundTag root = litematic(8, "first", first, "second", second);

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(2, rule(OAK, GLASS, 1, 1)));

    assertEquals(GLASS, stateAt(after, after.index(0, 1, 0)));
    assertEquals(GLASS, stateAt(after, after.index(3, 1, 0)));
    assertEquals(OAK, stateAt(after, after.index(4, 0, 0)));
  }

  @Test
  void removingABlockOfALaterRegionClearsWhatLayUnderIt() throws VolumeFormatException {
    CompoundTag under = region(new int[3], new int[] {2, 2, 1}, List.of(OAK), new int[4]);
    CompoundTag over =
        region(new int[3], new int[] {2, 2, 1}, List.of(STONE, GLASS), new int[] {0, 1, 0, 1});
    CompoundTag root = litematic(8, "under", under, "over", over);

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(2, rule(STONE, AIR, 1, 1)));

    assertEquals(STONE, stateAt(after, after.index(0, 0, 0)));
    assertEquals(AIR, stateAt(after, after.index(0, 1, 0)));
    assertEquals(GLASS, stateAt(after, after.index(1, 1, 0)));
  }

  @Test
  void aHiddenBlockChangesWithItsLayerToo() throws VolumeFormatException {
    CompoundTag under = region(new int[3], new int[] {1, 2, 1}, List.of(OAK), new int[2]);
    CompoundTag over = region(new int[3], new int[] {1, 2, 1}, List.of(STONE), new int[2]);
    CompoundTag root = litematic(4, "under", under, "over", over);

    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.LITEMATIC, root, changes(2, rule(OAK, SPRUCE, 0, 0)));

    assertEquals(
        List.of(OAK, SPRUCE),
        edited
            .compound("Regions")
            .orElseThrow()
            .compound("under")
            .orElseThrow()
            .compounds("BlockStatePalette")
            .stream()
            .map(BlockStateString::of)
            .toList());
  }

  private static List<String> numberedPalette(int entries) {
    List<String> palette = new ArrayList<>();
    palette.add(AIR);
    for (int index = 1; index < entries; index++) {
      palette.add("minecraft:block_" + index);
    }
    return palette;
  }

  private static CompoundTag paletteColumn(List<String> palette) {
    int[] cells = new int[palette.size() * 2];
    for (int index = 0; index < cells.length; index++) {
      cells[index] = index % palette.size();
    }
    return litematic(
        cells.length, "main", region(new int[3], new int[] {palette.size(), 2, 1}, palette, cells));
  }

  private static void assertWidth(CompoundTag edited, int entries, int cells) {
    CompoundTag region = edited.compound("Regions").orElseThrow().compound("main").orElseThrow();
    assertEquals(entries, region.compounds("BlockStatePalette").size());
    assertEquals(
        PackedStates.longs(cells, PackedStates.bits(entries)),
        region.longArray("BlockStates").length);
  }

  @Test
  void paletteGrowthCrossesTheBitWidthBoundaries() throws VolumeFormatException {
    // 4 entries take 2 bits, 5 take 3; 16 take 4, 17 take 5.
    for (int entries : new int[] {4, 16}) {
      List<String> palette = numberedPalette(entries);
      CompoundTag root = paletteColumn(palette);
      LayerChanges one = changes(2, rule("minecraft:block_1", GLASS, 1, 1));

      roundTrip(SchematicFormat.LITEMATIC, root, one);

      assertWidth(
          SchematicRewriter.rewrite(SchematicFormat.LITEMATIC, root, one),
          entries + 1,
          entries * 2);
    }
  }

  @Test
  void anIndexPastALongsBoundaryKeepsItsBits() throws VolumeFormatException {
    // 5 bits a cell: cell 12 spans two longs.
    List<String> palette = numberedPalette(17);
    CompoundTag root = paletteColumn(palette);

    roundTrip(SchematicFormat.LITEMATIC, root, changes(2, rule("minecraft:block_12", GLASS, 1, 1)));
  }

  @Test
  void aNewStateAlreadyInThePaletteTakesItsIndexInAnyPropertyOrder() throws VolumeFormatException {
    String file = "minecraft:spruce_stairs[half=top,facing=east]";
    String game = "minecraft:spruce_stairs[facing=east,half=top]";
    String oak = "minecraft:oak_stairs[facing=east,half=top]";
    LayerChanges changes =
        new LayerChanges(
            new int[] {0, 1},
            2,
            (band, state) -> band == 1 && state.equals(oak) ? new StateChange(game, true) : null);
    CompoundTag root =
        litematic(
            4,
            "main",
            region(new int[3], new int[] {2, 2, 1}, List.of(oak, file), new int[] {0, 1, 0, 1}));

    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.LITEMATIC, root, changes);

    assertEquals(
        2,
        edited
            .compound("Regions")
            .orElseThrow()
            .compound("main")
            .orElseThrow()
            .compounds("BlockStatePalette")
            .size());
  }

  @Test
  void moddedBlocksAndUneditedCellsKeepTheirOwnText() throws VolumeFormatException {
    CompoundTag root =
        litematic(
            4,
            "main",
            region(new int[3], new int[] {2, 2, 1}, List.of(MODDED, OAK), new int[] {0, 1, 0, 1}));

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(2, rule(OAK, SPRUCE, 1, 1)));

    assertEquals(MODDED, stateAt(after, after.index(0, 1, 0)));
    assertEquals(OAK, stateAt(after, after.index(1, 0, 0)));
  }

  @Test
  void aBlockEntityGoesOnlyWhereItsTypeChanged() throws VolumeFormatException {
    List<String> palette = List.of(AIR, CHEST, OAK_SIGN);
    int[] cells = {1, 2, 1, 2, 1, 2};
    CompoundTag root =
        litematic(
            6,
            "main",
            region(
                new int[3],
                new int[] {2, 3, 1},
                palette,
                cells,
                tile(0, 0, 0, CHEST),
                tile(0, 1, 0, CHEST),
                tile(1, 1, 0, "minecraft:sign"),
                tile(1, 2, 0, "minecraft:sign")));

    VoxelVolume after =
        roundTrip(
            SchematicFormat.LITEMATIC,
            root,
            changes(3, rule(CHEST, BARREL, 1, 1), rule(OAK_SIGN, SPRUCE_SIGN, 1, 1)));

    assertNotNull(after.blockEntities().get(after.index(0, 0, 0)), "below the range");
    assertNull(after.blockEntities().get(after.index(0, 1, 0)), "the chest became a barrel");
    assertNotNull(after.blockEntities().get(after.index(1, 1, 0)), "a sign stays a sign");
    assertNotNull(after.blockEntities().get(after.index(1, 2, 0)));
  }

  @Test
  void aBlockReplacedByAirLeavesNoBlockEntity() throws VolumeFormatException {
    CompoundTag root =
        litematic(
            2,
            "main",
            region(
                new int[3],
                new int[] {1, 2, 1},
                List.of(OAK_SIGN),
                new int[2],
                tile(0, 0, 0, "minecraft:sign"),
                tile(0, 1, 0, "minecraft:sign")));

    VoxelVolume after =
        roundTrip(SchematicFormat.LITEMATIC, root, changes(2, rule(OAK_SIGN, AIR, 1, 1)));

    assertNotNull(after.blockEntities().get(after.index(0, 0, 0)));
    assertNull(after.blockEntities().get(after.index(0, 1, 0)));
  }

  private static byte[] varints(int[] cells) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int cell : cells) {
      int value = cell;
      while (value >= 0x80) {
        bytes.write((value & 0x7F) | 0x80);
        value >>>= 7;
      }
      bytes.write(value);
    }
    return bytes.toByteArray();
  }

  private static CompoundTag sponge(
      int version, Map<String, Integer> palette, int[] size, int[] cells, ListTag blockEntities) {
    Map<String, NbtTag> paletteTags = new LinkedHashMap<>();
    palette.forEach((state, index) -> paletteTags.put(state, integer(index)));
    CompoundTag dimensions =
        compound(
            "Version", integer(version),
            "DataVersion", integer(3953),
            "Width", new ShortTag((short) size[0]),
            "Height", new ShortTag((short) size[1]),
            "Length", new ShortTag((short) size[2]));
    if (version >= 3) {
      return compound(
          "Schematic",
          dimensions.with(
              "Blocks",
              compound(
                  "Palette", new CompoundTag(paletteTags),
                  "Data", new ByteArrayTag(varints(cells)),
                  "BlockEntities", blockEntities)));
    }
    return dimensions
        .with("PaletteMax", integer(palette.size()))
        .with("Palette", new CompoundTag(paletteTags))
        .with("BlockData", new ByteArrayTag(varints(cells)))
        .with("BlockEntities", blockEntities);
  }

  private static Map<String, Integer> indexed(List<String> states) {
    Map<String, Integer> palette = new LinkedHashMap<>();
    states.forEach(state -> palette.put(state, palette.size()));
    return palette;
  }

  private static CompoundTag spongeChest(int x, int y, int z) {
    return compound("Pos", new IntArrayTag(new int[] {x, y, z}), "Id", string(CHEST));
  }

  private static CompoundTag spongeSample(int version) {
    return sponge(
        version,
        indexed(List.of(AIR, OAK, CHEST, MODDED)),
        new int[] {2, 3, 1},
        new int[] {1, 2, 1, 2, 3, 0},
        list(spongeChest(1, 0, 0), spongeChest(1, 1, 0)));
  }

  @Test
  void spongeRewritesItsCellsAndKeepsItsPalette() throws VolumeFormatException {
    for (int version : new int[] {1, 2, 3}) {
      LayerChanges changes = changes(3, rule(OAK, SPRUCE, 1, 2), rule(CHEST, BARREL, 1, 1));
      CompoundTag root = spongeSample(version);

      VoxelVolume after = roundTrip(SchematicFormat.SCHEM, root, changes);

      assertEquals(OAK, stateAt(after, after.index(0, 0, 0)));
      assertEquals(MODDED, stateAt(after, after.index(0, 2, 0)));
      assertNotNull(after.blockEntities().get(after.index(1, 0, 0)), "below the range");
      assertNull(after.blockEntities().get(after.index(1, 1, 0)), "the chest became a barrel");
    }
  }

  @Test
  void spongeCountsItsNewPaletteEntries() throws VolumeFormatException {
    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.SCHEM, spongeSample(2), changes(3, rule(OAK, SPRUCE, 1, 2)));

    assertEquals(5, edited.intValue("PaletteMax", 0));
    assertEquals(4, edited.compound("Palette").orElseThrow().intValue(SPRUCE, -1));
    assertEquals(0, edited.compound("Palette").orElseThrow().intValue(AIR, -1));
  }

  @Test
  void spongeKeepsItsNesting() throws VolumeFormatException {
    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.SCHEM, spongeSample(3), changes(3, rule(OAK, SPRUCE, 1, 2)));

    CompoundTag blocks =
        edited.compound("Schematic").orElseThrow().compound("Blocks").orElseThrow();
    assertEquals(5, blocks.compound("Palette").orElseThrow().values().size());
  }

  @Test
  void spongeBlockEntitiesUnderTileEntitiesFollowTheirCells() throws VolumeFormatException {
    CompoundTag root =
        sponge(2, indexed(List.of(AIR, CHEST)), new int[] {1, 2, 1}, new int[] {1, 1}, list())
            .without(Set.of("BlockEntities"))
            .with("TileEntities", list(spongeChest(0, 0, 0), spongeChest(0, 1, 0)));

    VoxelVolume after = roundTrip(SchematicFormat.SCHEM, root, changes(2, rule(CHEST, AIR, 1, 1)));

    assertNotNull(after.blockEntities().get(after.index(0, 0, 0)));
    assertNull(after.blockEntities().get(after.index(0, 1, 0)));
  }

  @Test
  void spongeIndicesGrowPastOneVarintByte() throws VolumeFormatException {
    List<String> palette = numberedPalette(128);
    int[] cells = new int[palette.size() * 2];
    for (int index = 0; index < cells.length; index++) {
      cells[index] = index % palette.size();
    }
    CompoundTag root = sponge(2, indexed(palette), new int[] {palette.size(), 2, 1}, cells, list());

    roundTrip(SchematicFormat.SCHEM, root, changes(2, rule("minecraft:block_5", GLASS, 1, 1)));
  }

  @Test
  void spongeAirNeverLandsOnAnIndexThePaletteSkips() throws VolumeFormatException {
    Map<String, Integer> palette = new LinkedHashMap<>();
    palette.put(OAK, 1);
    palette.put(STONE, 3);
    CompoundTag root =
        sponge(2, palette, new int[] {2, 1, 1}, new int[] {1, 3}, list())
            .with("PaletteMax", integer(4));

    CompoundTag edited =
        SchematicRewriter.rewrite(SchematicFormat.SCHEM, root, changes(1, rule(OAK, AIR, 0, 0)));

    assertEquals(4, edited.compound("Palette").orElseThrow().intValue(AIR, -1));
  }

  private static CompoundTag structureBlock(int x, int y, int z, int state, boolean nbt) {
    CompoundTag block =
        compound("pos", list(integer(x), integer(y), integer(z)), "state", integer(state));
    return nbt ? block.with("nbt", compound("id", string(CHEST))) : block;
  }

  private static CompoundTag structure(NbtTag palettes, boolean several) {
    return compound(
        "DataVersion",
        integer(3953),
        "size",
        list(integer(2), integer(3), integer(1)),
        several ? "palettes" : "palette",
        palettes,
        "blocks",
        list(
            structureBlock(0, 0, 0, 0, false),
            structureBlock(1, 0, 0, 1, true),
            structureBlock(0, 1, 0, 0, false),
            structureBlock(1, 1, 0, 1, true),
            structureBlock(0, 2, 0, 2, false),
            structureBlock(1, 2, 0, 1, true)),
        "entities",
        list(compound("nbt", compound("id", string("minecraft:cow")))));
  }

  private static final List<String> STRUCTURE_PALETTE = List.of(OAK, CHEST, STONE);

  @Test
  void aStructureTakesEachEntrysStateByItsLayer() throws VolumeFormatException {
    CompoundTag root = structure(paletteTag(STRUCTURE_PALETTE), false);

    VoxelVolume after =
        roundTrip(
            SchematicFormat.NBT,
            root,
            changes(3, rule(OAK, SPRUCE, 1, 1), rule(CHEST, BARREL, 1, 1)));
    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.NBT,
            root,
            changes(3, rule(OAK, SPRUCE, 1, 1), rule(CHEST, BARREL, 1, 1)));

    assertEquals(5, edited.compounds("palette").size());
    assertEquals(6, edited.compounds("blocks").size());
    assertEquals(1, edited.list("entities").size());
    assertFalse(edited.compounds("blocks").get(3).contains("nbt"), "the chest became a barrel");
    assertTrue(edited.compounds("blocks").get(1).contains("nbt"), "below the range");
    assertNotNull(after.blockEntities().get(after.index(1, 2, 0)));
    assertNull(after.blockEntities().get(after.index(1, 1, 0)));
  }

  @Test
  void aStructureBlockReplacedByAirLosesItsEntry() throws VolumeFormatException {
    CompoundTag root = structure(paletteTag(STRUCTURE_PALETTE), false);
    LayerChanges changes = changes(3, rule(CHEST, AIR, 1, 2));

    roundTrip(SchematicFormat.NBT, root, changes);
    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.NBT, root, changes);

    assertEquals(4, edited.compounds("blocks").size());
  }

  @Test
  void everyPaletteOfAStructureGrowsAtTheSameIndex() throws VolumeFormatException {
    List<String> variant = List.of("minecraft:birch_planks", CHEST, STONE);
    CompoundTag root = structure(list(paletteTag(STRUCTURE_PALETTE), paletteTag(variant)), true);
    Map<String, StateChange> byState =
        Map.of(
            OAK,
            new StateChange(SPRUCE, true),
            "minecraft:birch_planks",
            new StateChange(GLASS, true));
    LayerChanges changes =
        new LayerChanges(
            new int[] {0, 1, 0}, 2, (band, state) -> band == 1 ? byState.get(state) : null);

    roundTrip(SchematicFormat.NBT, root, changes);
    CompoundTag edited = SchematicRewriter.rewrite(SchematicFormat.NBT, root, changes);

    List<NbtTag> palettes = edited.list("palettes");
    assertEquals(
        List.of(OAK, CHEST, STONE, SPRUCE),
        ((ListTag) palettes.get(0)).compounds().stream().map(BlockStateString::of).toList());
    assertEquals(
        List.of("minecraft:birch_planks", CHEST, STONE, GLASS),
        ((ListTag) palettes.get(1)).compounds().stream().map(BlockStateString::of).toList());
    assertEquals(3, edited.compounds("blocks").get(2).intValue("state", -1));
  }

  @Test
  void aStructureEntryStaysWhileAnotherPaletteHoldsABlockThere() throws VolumeFormatException {
    List<String> variant = List.of("minecraft:birch_planks", CHEST, STONE);
    CompoundTag root = structure(list(paletteTag(STRUCTURE_PALETTE), paletteTag(variant)), true);

    CompoundTag edited =
        SchematicRewriter.rewrite(SchematicFormat.NBT, root, changes(3, rule(OAK, AIR, 1, 1)));

    assertEquals(6, edited.compounds("blocks").size());
    ListTag first = (ListTag) edited.list("palettes").get(0);
    assertEquals(AIR, BlockStateString.of(first.compounds().get(3)));
  }

  /** {@code stone, dirt} on each of two layers, as an MCEdit file: stone is id 1, dirt id 3. */
  private static CompoundTag mcedit() {
    return compound(
        "Width", new ShortTag((short) 2),
        "Height", new ShortTag((short) 2),
        "Length", new ShortTag((short) 1),
        "Blocks", new ByteArrayTag(new byte[] {1, 3, 1, 3}),
        "Data", new ByteArrayTag(new byte[4]));
  }

  @Test
  void aLegacyFileIsWrittenFromTheVolumeWithItsBandsApplied() throws VolumeFormatException {
    VoxelVolume before = read(SchematicFormat.SCHEMATIC, mcedit());
    LayerChanges changes = changes(2, rule(STONE, GLASS, 1, 1), rule(DIRT, AIR, 1, 1));
    List<List<String>> statesByBand = new ArrayList<>();
    for (int band = 0; band < changes.bands(); band++) {
      List<String> states = new ArrayList<>();
      for (String state : before.palette()) {
        StateChange change = changes.mapper().change(band, state);
        states.add(change == null ? state : change.state());
      }
      statesByBand.add(states);
    }

    CompoundTag written =
        LitematicWriter.write(
            new LitematicWriter.Metadata("Legacy", "", "", 0, 4325),
            LitematicWriter.Contents.ofBands(
                2, 2, 1, statesByBand, before.cells(), changes.bandOfY(), Map.of()));

    assertCells(before, read(SchematicFormat.LITEMATIC, written), changes);
    assertEquals(3, written.compound("Metadata").orElseThrow().intValue("TotalBlocks", -1));
  }

  private static byte[] withoutPalette(CompoundTag root, Set<String> palette) {
    return NbtFixtures.gzip(strip(root, palette));
  }

  /** The tree without the palette, at any depth. */
  private static CompoundTag strip(CompoundTag tree, Set<String> palette) {
    Map<String, NbtTag> kept = new LinkedHashMap<>();
    tree.values()
        .forEach(
            (key, value) -> {
              if (!palette.contains(key)) {
                kept.put(key, value instanceof CompoundTag inner ? strip(inner, palette) : value);
              }
            });
    return new CompoundTag(kept);
  }

  private static void assertOnlyThePaletteChanges(
      SchematicFormat format, CompoundTag root, Set<String> palette) throws VolumeFormatException {
    StateMapper planks = state -> state.equals(OAK) ? new StateChange(GLASS, true) : null;
    CompoundTag edited = SchematicRewriter.rewrite(format, root, planks);

    assertArrayEquals(withoutPalette(root, palette), withoutPalette(edited, palette));
  }

  @Test
  void aWholeDesignChangeOnlyTouchesThePalette() throws VolumeFormatException {
    assertOnlyThePaletteChanges(SchematicFormat.LITEMATIC, column(), Set.of("BlockStatePalette"));
    assertOnlyThePaletteChanges(SchematicFormat.SCHEM, spongeSample(2), Set.of("Palette"));
    assertOnlyThePaletteChanges(SchematicFormat.SCHEM, spongeSample(3), Set.of("Palette"));
    assertOnlyThePaletteChanges(
        SchematicFormat.NBT, structure(paletteTag(STRUCTURE_PALETTE), false), Set.of("palette"));
  }

  @Test
  void aWholeDesignChangeSavesWhatARangeOverEveryLayerSaves() throws VolumeFormatException {
    CompoundTag edited =
        SchematicRewriter.rewrite(
            SchematicFormat.LITEMATIC,
            column(),
            state -> state.equals(OAK) ? new StateChange(SPRUCE, true) : null);

    assertCells(
        read(SchematicFormat.LITEMATIC, column()),
        read(SchematicFormat.LITEMATIC, edited),
        changes(6, rule(OAK, SPRUCE, 0, 5)));
  }
}
