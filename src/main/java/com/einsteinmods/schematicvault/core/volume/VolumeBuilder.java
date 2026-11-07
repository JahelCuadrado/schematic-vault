package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects a design's blocks into a {@link VoxelVolume}: one shared palette (air first), the cells,
 * the block entities and the file's block count. The box is checked against {@link
 * VoxelVolume#MAX_CELLS} before anything is allocated.
 */
final class VolumeBuilder {
  private static final String ID_KEY = "id";
  private static final String ITEMS_KEY = "Items";

  /** Block entities whose renderers draw their items: every other one drops its inventory. */
  private static final Set<String> RENDERED_ITEMS = Set.of("minecraft:campfire", "minecraft:shelf");

  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final int[] cells;
  private final List<String> palette = new ArrayList<>();
  private final Map<String, Integer> paletteIndices = new HashMap<>();
  private final Map<Integer, CompoundTag> blockEntities = new HashMap<>();
  private long blockCount;

  private VolumeBuilder(int sizeX, int sizeY, int sizeZ) {
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.cells = new int[sizeX * sizeY * sizeZ];
    palette.add(VoxelVolume.AIR_STATE);
  }

  /** A builder for a box of that size, refused when a side or the volume is too big. */
  static VolumeBuilder of(long sizeX, long sizeY, long sizeZ) throws VolumeFormatException {
    if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
      throw VolumeFormatException.unreadable("negative size " + sizeX + "x" + sizeY + "x" + sizeZ);
    }
    long longest = Math.max(sizeX, Math.max(sizeY, sizeZ));
    if (longest > VoxelVolume.MAX_SIDE || sizeX * sizeY * sizeZ > VoxelVolume.MAX_CELLS) {
      throw VolumeFormatException.tooLarge("box " + sizeX + "x" + sizeY + "x" + sizeZ);
    }
    return new VolumeBuilder(
        Math.toIntExact(sizeX), Math.toIntExact(sizeY), Math.toIntExact(sizeZ));
  }

  int sizeX() {
    return sizeX;
  }

  int sizeY() {
    return sizeY;
  }

  int sizeZ() {
    return sizeZ;
  }

  /** The shared palette index of {@code state}; {@link VoxelVolume#AIR} for any air block. */
  int paletteIndex(String state) throws VolumeFormatException {
    if (BlockStateString.isAir(state)) {
      return VoxelVolume.AIR;
    }
    Integer known = paletteIndices.get(state);
    if (known != null) {
      return known;
    }
    if (palette.size() >= VoxelVolume.MAX_PALETTE) {
      throw VolumeFormatException.unreadable("over " + VoxelVolume.MAX_PALETTE + " block states");
    }
    int index = palette.size();
    palette.add(state);
    paletteIndices.put(state, index);
    return index;
  }

  boolean contains(int x, int y, int z) {
    return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
  }

  /** Places a block; a position outside the box is ignored. */
  void set(int x, int y, int z, int paletteIndex) {
    if (contains(x, y, z)) {
      cells[(y * sizeZ + z) * sizeX + x] = paletteIndex;
    }
  }

  /** Counts non-air blocks of the file, as the API counts them. */
  void countBlocks(long count) {
    blockCount += count;
  }

  /**
   * Keeps a block entity's NBT (without an inventory its renderer does not draw); a position
   * outside the box is ignored.
   */
  void blockEntity(int x, int y, int z, CompoundTag nbt) {
    if (!contains(x, y, z)) {
      return;
    }
    String id = nbt.string(ID_KEY).orElse("");
    CompoundTag kept =
        nbt.contains(ITEMS_KEY) && !RENDERED_ITEMS.contains(id)
            ? nbt.without(Set.of(ITEMS_KEY))
            : nbt;
    blockEntities.put((y * sizeZ + z) * sizeX + x, kept);
  }

  VoxelVolume build(int dataVersion, int blockEntityDataVersion) {
    int[] counts = new int[palette.size()];
    for (int cell : cells) {
      counts[cell]++;
    }
    return new VoxelVolume(
        sizeX,
        sizeY,
        sizeZ,
        palette,
        cells,
        counts,
        blockEntities,
        dataVersion,
        blockEntityDataVersion,
        blockCount);
  }
}
