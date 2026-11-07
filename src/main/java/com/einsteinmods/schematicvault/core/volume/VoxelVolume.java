package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import java.util.List;
import java.util.Map;

/**
 * A design's blocks in one box, whatever format they came from: a palette of block-state strings
 * (entry {@link #AIR} is always air) and one palette index per cell, x fastest, then z, then y
 * ({@link #index}). Block entities keep only the NBT their renderers read. Immutable by contract:
 * {@link #cells()} is the volume's own array, never written after it is built.
 *
 * @param palette block states as text ({@link BlockStateString})
 * @param paletteCounts how many cells hold each palette entry
 * @param blockEntities block-entity NBT by cell index
 * @param dataVersion game data version the block states were saved with; 0 when unknown
 * @param blockEntityDataVersion game data version of the block-entity NBT; 0 when unknown
 * @param blockCount non-air blocks the file holds (overlapping regions counted twice, as the API
 *     counts them)
 */
public record VoxelVolume(
    int sizeX,
    int sizeY,
    int sizeZ,
    List<String> palette,
    int[] cells,
    int[] paletteCounts,
    Map<Integer, CompoundTag> blockEntities,
    int dataVersion,
    int blockEntityDataVersion,
    long blockCount) {
  /** Palette index of air. */
  public static final int AIR = 0;

  public static final String AIR_STATE = "minecraft:air";

  /** Largest box read: 32 M cells, a 128 MB index array. */
  public static final int MAX_CELLS = 1 << 25;

  /** Longest side read: the game's own structure limit is far below this. */
  public static final int MAX_SIDE = 1 << 14;

  /** Most distinct block states one design may use. */
  public static final int MAX_PALETTE = 1 << 20;

  public VoxelVolume {
    palette = List.copyOf(palette);
    blockEntities = Map.copyOf(blockEntities);
  }

  public int index(int x, int y, int z) {
    return (y * sizeZ + z) * sizeX + x;
  }

  /** Cells in the box. */
  public int volume() {
    return cells.length;
  }
}
