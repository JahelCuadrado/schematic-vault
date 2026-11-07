package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.StringTag;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes one box of blocks as a Litematica schematic, version 6 (sub-version 1, what Litematica
 * writes for it): one region at the origin named after the design, its palette (air first, only the
 * states used, each once) and packed {@code BlockStates} ({@link PackedStates}), the block entities
 * with their positions in the region, no entities or pending ticks, and the metadata Litematica
 * lists (name, author, sizes, block count, times). The states and the block-entity NBT must already
 * be in the form of {@code dataVersion}: the game's own data version when the client converted
 * them.
 */
public final class LitematicWriter {
  public static final int VERSION = 6;
  public static final int SUB_VERSION = 1;

  private static final int REGION_COUNT = 1;
  private static final String POSITION = "Position";
  private static final String SIZE = "Size";
  private static final String PALETTE = "BlockStatePalette";
  private static final String BLOCK_STATES = "BlockStates";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String ENTITIES = "Entities";
  private static final String PENDING_BLOCK_TICKS = "PendingBlockTicks";
  private static final String PENDING_FLUID_TICKS = "PendingFluidTicks";
  private static final String NAME = "Name";
  private static final String AUTHOR = "Author";
  private static final String DESCRIPTION = "Description";
  private static final String REGION_COUNT_KEY = "RegionCount";
  private static final String TOTAL_VOLUME = "TotalVolume";
  private static final String TOTAL_BLOCKS = "TotalBlocks";
  private static final String TIME_CREATED = "TimeCreated";
  private static final String TIME_MODIFIED = "TimeModified";
  private static final String ENCLOSING_SIZE = "EnclosingSize";
  private static final String DATA_VERSION = "MinecraftDataVersion";
  private static final String VERSION_KEY = "Version";
  private static final String SUB_VERSION_KEY = "SubVersion";
  private static final String METADATA = "Metadata";
  private static final String REGIONS = "Regions";
  private static final String X = "x";
  private static final String Y = "y";
  private static final String Z = "z";

  private LitematicWriter() {}

  /**
   * What Litematica shows about the design.
   *
   * @param timeMillis creation and modification time, epoch milliseconds
   * @param dataVersion the game data version the states and block entities are in
   */
  public record Metadata(
      String name, String author, String description, long timeMillis, int dataVersion) {}

  /**
   * The blocks: a box laid out as {@link VoxelVolume} (x fastest, then z, then y).
   *
   * @param palette block states as text; any air entry is written as air
   * @param cells a palette index per cell
   * @param blockEntities block-entity NBT without positions, by cell index; dropped on air cells
   */
  public record Contents(
      int sizeX,
      int sizeY,
      int sizeZ,
      List<String> palette,
      int[] cells,
      Map<Integer, CompoundTag> blockEntities) {
    public Contents {
      palette = List.copyOf(palette);
      blockEntities = Map.copyOf(blockEntities);
      if ((long) sizeX * sizeY * sizeZ != cells.length) {
        throw new IllegalArgumentException("cells do not fill the box");
      }
    }

    /**
     * The blocks of a volume whose layers read different palettes ({@link RuleBands}): one palette
     * with every band's entries in turn, the cells copied to point there (saving is the one place
     * cells are copied). {@link #write} keeps only the states used, each once.
     *
     * @param statesByBand each band's palette, all of the same size
     * @param bandOfY the band each Y layer reads
     */
    public static Contents ofBands(
        int sizeX,
        int sizeY,
        int sizeZ,
        List<List<String>> statesByBand,
        int[] cells,
        int[] bandOfY,
        Map<Integer, CompoundTag> blockEntities) {
      if (statesByBand.size() == 1) {
        return new Contents(sizeX, sizeY, sizeZ, statesByBand.getFirst(), cells, blockEntities);
      }
      List<String> palette = new ArrayList<>();
      statesByBand.forEach(palette::addAll);
      int entries = statesByBand.getFirst().size();
      int layerCells = sizeX * sizeZ;
      int[] banded = new int[cells.length];
      for (int y = 0; y < sizeY; y++) {
        int firstEntry = bandOfY[y] * entries;
        int end = (y + 1) * layerCells;
        for (int index = y * layerCells; index < end; index++) {
          banded[index] = cells[index] + firstEntry;
        }
      }
      return new Contents(sizeX, sizeY, sizeZ, palette, banded, blockEntities);
    }
  }

  public static CompoundTag write(Metadata metadata, Contents contents) {
    List<String> palette = contents.palette();
    int[] oldToNew = new int[palette.size()];
    boolean[] used = new boolean[palette.size()];
    for (int cell : contents.cells()) {
      used[cell] = true;
    }
    List<NbtTag> entries = new ArrayList<>();
    entries.add(BlockStateString.compound(VoxelVolume.AIR_STATE));
    Map<String, Integer> byState = new HashMap<>();
    for (int index = 0; index < palette.size(); index++) {
      String state = palette.get(index);
      if (!used[index] || BlockStateString.isAir(state)) {
        oldToNew[index] = VoxelVolume.AIR;
        continue;
      }
      Integer known = byState.get(state);
      if (known == null) {
        known = entries.size();
        entries.add(BlockStateString.compound(state));
        byState.put(state, known);
      }
      oldToNew[index] = known;
    }
    int[] cells = new int[contents.cells().length];
    long blocks = 0;
    for (int index = 0; index < cells.length; index++) {
      int cell = oldToNew[contents.cells()[index]];
      cells[index] = cell;
      if (cell != VoxelVolume.AIR) {
        blocks++;
      }
    }

    int sizeX = contents.sizeX();
    int sizeZ = contents.sizeZ();
    List<NbtTag> blockEntities = new ArrayList<>();
    for (Map.Entry<Integer, CompoundTag> entry :
        new TreeMap<>(contents.blockEntities()).entrySet()) {
      int index = entry.getKey();
      if (index < 0 || index >= cells.length || cells[index] == VoxelVolume.AIR) {
        continue;
      }
      Map<String, NbtTag> blockEntity = new LinkedHashMap<>();
      blockEntity.put(X, new IntTag(index % sizeX));
      blockEntity.put(Y, new IntTag(index / sizeX / sizeZ));
      blockEntity.put(Z, new IntTag(index / sizeX % sizeZ));
      for (Map.Entry<String, NbtTag> value : entry.getValue().values().entrySet()) {
        blockEntity.putIfAbsent(value.getKey(), value.getValue());
      }
      blockEntities.add(new CompoundTag(blockEntity));
    }

    CompoundTag size = position(sizeX, contents.sizeY(), sizeZ);
    Map<String, NbtTag> region = new LinkedHashMap<>();
    region.put(POSITION, position(0, 0, 0));
    region.put(SIZE, size);
    region.put(PALETTE, new ListTag(entries));
    region.put(BLOCK_STATES, new LongArrayTag(PackedStates.pack(cells, entries.size())));
    region.put(TILE_ENTITIES, new ListTag(blockEntities));
    region.put(ENTITIES, new ListTag(List.of()));
    region.put(PENDING_BLOCK_TICKS, new ListTag(List.of()));
    region.put(PENDING_FLUID_TICKS, new ListTag(List.of()));

    Map<String, NbtTag> meta = new LinkedHashMap<>();
    meta.put(NAME, new StringTag(metadata.name()));
    meta.put(AUTHOR, new StringTag(metadata.author()));
    meta.put(DESCRIPTION, new StringTag(metadata.description()));
    meta.put(REGION_COUNT_KEY, new IntTag(REGION_COUNT));
    meta.put(TOTAL_VOLUME, new IntTag(cells.length));
    meta.put(TOTAL_BLOCKS, new IntTag((int) blocks));
    meta.put(TIME_CREATED, new LongTag(metadata.timeMillis()));
    meta.put(TIME_MODIFIED, new LongTag(metadata.timeMillis()));
    meta.put(ENCLOSING_SIZE, size);

    Map<String, NbtTag> root = new LinkedHashMap<>();
    root.put(DATA_VERSION, new IntTag(metadata.dataVersion()));
    root.put(VERSION_KEY, new IntTag(VERSION));
    root.put(SUB_VERSION_KEY, new IntTag(SUB_VERSION));
    root.put(METADATA, new CompoundTag(meta));
    root.put(REGIONS, new CompoundTag(Map.of(metadata.name(), new CompoundTag(region))));
    return new CompoundTag(root);
  }

  private static CompoundTag position(int x, int y, int z) {
    Map<String, NbtTag> values = new LinkedHashMap<>();
    values.put(X, new IntTag(x));
    values.put(Y, new IntTag(y));
    values.put(Z, new IntTag(z));
    return new CompoundTag(values);
  }
}
