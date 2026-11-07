package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads Litematica's {@code .litematic} (versions 4 to 7). Each region has its own palette and a
 * packed array of palette indices ({@code BlockStates}): {@code max(2, ceil(log2(palette size)))}
 * bits per cell, low bits first, an entry may span two longs. A region's size may be negative on
 * any axis (it then extends back from its position). Regions are merged into one box by position; a
 * later region's blocks replace an earlier one's, never with air.
 */
public final class LitematicReader {
  private static final int MIN_VERSION = 4;
  private static final int MAX_VERSION = 7;
  private static final int MIN_BITS = 2;

  /** Bit index to long index: log2(64). */
  private static final int LONG_INDEX_SHIFT = Integer.numberOfTrailingZeros(Long.SIZE);

  private static final String VERSION = "Version";
  private static final String DATA_VERSION = "MinecraftDataVersion";
  private static final String REGIONS = "Regions";
  private static final String POSITION = "Position";
  private static final String SIZE = "Size";
  private static final String PALETTE = "BlockStatePalette";
  private static final String BLOCK_STATES = "BlockStates";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String X = "x";
  private static final String Y = "y";
  private static final String Z = "z";
  private static final Set<String> POSITION_KEYS = Set.of(X, Y, Z);

  private LitematicReader() {}

  public static VoxelVolume read(CompoundTag root) throws VolumeFormatException {
    int version = root.intValue(VERSION, 0);
    if (version < MIN_VERSION || version > MAX_VERSION) {
      throw VolumeFormatException.unreadable("litematic version " + version);
    }
    CompoundTag regions = root.compound(REGIONS).orElse(null);
    if (regions == null) {
      throw VolumeFormatException.unreadable("no regions");
    }
    List<Region> parsed = new ArrayList<>();
    for (NbtTag entry : regions.values().values()) {
      if (entry instanceof CompoundTag region) {
        parsed.add(Region.of(region));
      }
    }
    if (parsed.isEmpty()) {
      throw VolumeFormatException.unreadable("no regions");
    }
    long minX = Long.MAX_VALUE;
    long minY = Long.MAX_VALUE;
    long minZ = Long.MAX_VALUE;
    long maxX = Long.MIN_VALUE;
    long maxY = Long.MIN_VALUE;
    long maxZ = Long.MIN_VALUE;
    for (Region region : parsed) {
      minX = Math.min(minX, region.originX);
      minY = Math.min(minY, region.originY);
      minZ = Math.min(minZ, region.originZ);
      maxX = Math.max(maxX, region.originX + region.sizeX);
      maxY = Math.max(maxY, region.originY + region.sizeY);
      maxZ = Math.max(maxZ, region.originZ + region.sizeZ);
    }
    VolumeBuilder builder = VolumeBuilder.of(maxX - minX, maxY - minY, maxZ - minZ);
    for (Region region : parsed) {
      place(
          builder,
          region,
          Math.toIntExact(region.originX - minX),
          Math.toIntExact(region.originY - minY),
          Math.toIntExact(region.originZ - minZ));
    }
    int dataVersion = root.intValue(DATA_VERSION, 0);
    return builder.build(dataVersion, dataVersion);
  }

  private static void place(
      VolumeBuilder builder, Region region, int offsetX, int offsetY, int offsetZ)
      throws VolumeFormatException {
    List<CompoundTag> palette = region.nbt.compounds(PALETTE);
    long volume = (long) region.sizeX * region.sizeY * region.sizeZ;
    if (palette.isEmpty() || volume == 0) {
      return;
    }
    int[] shared = new int[palette.size()];
    for (int index = 0; index < shared.length; index++) {
      shared[index] = builder.paletteIndex(BlockStateString.of(palette.get(index)));
    }
    int bits = Math.max(MIN_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
    long[] packed = region.nbt.longArray(BLOCK_STATES);
    if (packed == null || (long) packed.length * Long.SIZE < volume * bits) {
      throw VolumeFormatException.unreadable("block state array shorter than its region");
    }
    long mask = (1L << bits) - 1;
    long blocks = 0;
    int index = 0;
    for (int y = 0; y < region.sizeY; y++) {
      for (int z = 0; z < region.sizeZ; z++) {
        for (int x = 0; x < region.sizeX; x++, index++) {
          long bitIndex = (long) index * bits;
          int word = (int) (bitIndex >>> LONG_INDEX_SHIFT);
          int shift = (int) (bitIndex & (Long.SIZE - 1));
          long value = packed[word] >>> shift;
          if (shift + bits > Long.SIZE) {
            value |= packed[word + 1] << (Long.SIZE - shift);
          }
          int local = (int) (value & mask);
          if (local >= shared.length) {
            throw VolumeFormatException.unreadable("block state outside its palette");
          }
          int state = shared[local];
          if (state != VoxelVolume.AIR) {
            blocks++;
            builder.set(offsetX + x, offsetY + y, offsetZ + z, state);
          }
        }
      }
    }
    builder.countBlocks(blocks);
    for (CompoundTag blockEntity : region.nbt.compounds(TILE_ENTITIES)) {
      builder.blockEntity(
          offsetX + blockEntity.intValue(X, -1),
          offsetY + blockEntity.intValue(Y, -1),
          offsetZ + blockEntity.intValue(Z, -1),
          blockEntity.without(POSITION_KEYS));
    }
  }

  /**
   * A region with its box: the minimum corner and the (positive) size. A cell's place in the design
   * is its region's corner less the lowest corner of all regions, plus its position in the region:
   * {@code BlockStates} starts at the minimum corner whatever the size's sign.
   */
  public record Region(
      CompoundTag nbt, long originX, long originY, long originZ, int sizeX, int sizeY, int sizeZ) {
    public static Region of(CompoundTag nbt) throws VolumeFormatException {
      CompoundTag position = nbt.compound(POSITION).orElse(null);
      CompoundTag size = nbt.compound(SIZE).orElse(null);
      if (position == null || size == null) {
        throw VolumeFormatException.unreadable("region without position or size");
      }
      int x = size.intValue(X, 0);
      int y = size.intValue(Y, 0);
      int z = size.intValue(Z, 0);
      if (Math.max(Math.abs((long) x), Math.max(Math.abs((long) y), Math.abs((long) z)))
          > VoxelVolume.MAX_SIDE) {
        throw VolumeFormatException.tooLarge("region " + x + "x" + y + "x" + z);
      }
      // A negative size extends backwards from the position: the region starts size + 1 before it.
      return new Region(
          nbt,
          position.intValue(X, 0) + (long) Math.min(0, x + 1),
          position.intValue(Y, 0) + (long) Math.min(0, y + 1),
          position.intValue(Z, 0) + (long) Math.min(0, z + 1),
          Math.abs(x),
          Math.abs(y),
          Math.abs(z));
    }
  }
}
