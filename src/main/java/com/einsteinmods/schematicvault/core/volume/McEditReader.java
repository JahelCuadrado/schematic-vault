package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads MCEdit / Schematica {@code .schematic} files: pre-1.13 numeric blocks, one byte of id per
 * cell ({@code Blocks}, x fastest, then z, then y), four more id bits per cell in the optional
 * {@code AddBlocks} nibbles, and four data bits per cell ({@code Data}), mapped to block states by
 * {@link LegacyBlocks}. A Sponge schematic saved with this extension is read as one.
 */
public final class McEditReader {
  /** The legacy table's states are 1.13 states (data version 1519). */
  private static final int STATES_DATA_VERSION = 1519;

  /** Block entities were saved by 1.12.2 or older (data version 1343). */
  private static final int BLOCK_ENTITY_DATA_VERSION = 1343;

  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
  private static final int BYTE_MASK = 0xFF;
  private static final int NIBBLE_MASK = 0x0F;
  private static final int NIBBLE_BITS = 4;
  private static final int AIR_ID = 0;
  private static final int DATA_BITS = 4;
  private static final String WIDTH = "Width";
  private static final String HEIGHT = "Height";
  private static final String LENGTH = "Length";
  private static final String BLOCKS = "Blocks";
  private static final String DATA = "Data";
  private static final String ADD_BLOCKS = "AddBlocks";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String X = "x";
  private static final String Y = "y";
  private static final String Z = "z";
  private static final Set<String> POSITION_KEYS = Set.of(X, Y, Z);

  private McEditReader() {}

  public static VoxelVolume read(CompoundTag root, LegacyBlocks legacy)
      throws VolumeFormatException {
    if (SpongeReader.isSponge(root)) {
      return SpongeReader.read(root);
    }
    VolumeBuilder builder =
        VolumeBuilder.of(
            root.intValue(WIDTH, 0) & UNSIGNED_SHORT_MASK,
            root.intValue(HEIGHT, 0) & UNSIGNED_SHORT_MASK,
            root.intValue(LENGTH, 0) & UNSIGNED_SHORT_MASK);
    int volume = builder.sizeX() * builder.sizeY() * builder.sizeZ();
    byte[] ids = root.byteArray(BLOCKS);
    byte[] data = root.byteArray(DATA);
    byte[] add = root.byteArray(ADD_BLOCKS);
    if (ids == null || data == null || ids.length < volume || data.length < volume) {
      throw VolumeFormatException.unreadable("block data shorter than the schematic");
    }
    if (add != null && add.length < (volume + 1) / 2) {
      throw VolumeFormatException.unreadable("AddBlocks shorter than the schematic");
    }
    Map<Integer, Integer> paletteByPair = new HashMap<>();
    long blocks = 0;
    int index = 0;
    for (int y = 0; y < builder.sizeY(); y++) {
      for (int z = 0; z < builder.sizeZ(); z++) {
        for (int x = 0; x < builder.sizeX(); x++, index++) {
          int id = ids[index] & BYTE_MASK;
          if (add != null) {
            int nibbles = add[index >> 1] & BYTE_MASK;
            int high = (index & 1) == 0 ? nibbles & NIBBLE_MASK : nibbles >> NIBBLE_BITS;
            id |= high << Byte.SIZE;
          }
          if (id == AIR_ID) {
            continue;
          }
          int meta = data[index] & NIBBLE_MASK;
          int pair = id << DATA_BITS | meta;
          Integer state = paletteByPair.get(pair);
          if (state == null) {
            state = builder.paletteIndex(legacy.state(id, meta));
            paletteByPair.put(pair, state);
          }
          if (state != VoxelVolume.AIR) {
            blocks++;
            builder.set(x, y, z, state);
          }
        }
      }
    }
    builder.countBlocks(blocks);
    for (CompoundTag blockEntity : root.compounds(TILE_ENTITIES)) {
      builder.blockEntity(
          blockEntity.intValue(X, -1),
          blockEntity.intValue(Y, -1),
          blockEntity.intValue(Z, -1),
          blockEntity);
    }
    return builder.build(STATES_DATA_VERSION, BLOCK_ENTITY_DATA_VERSION);
  }

  /**
   * The whole NBT of an MCEdit file's block entities (inventories too, which {@link #read} drops),
   * without their positions, by cell index of the box; block entities outside it are left out.
   * Still in the 1.12 form ({@link #BLOCK_ENTITY_DATA_VERSION}).
   */
  public static Map<Integer, CompoundTag> blockEntities(CompoundTag root) {
    int sizeX = root.intValue(WIDTH, 0) & UNSIGNED_SHORT_MASK;
    int sizeY = root.intValue(HEIGHT, 0) & UNSIGNED_SHORT_MASK;
    int sizeZ = root.intValue(LENGTH, 0) & UNSIGNED_SHORT_MASK;
    Map<Integer, CompoundTag> blockEntities = new HashMap<>();
    for (CompoundTag blockEntity : root.compounds(TILE_ENTITIES)) {
      int x = blockEntity.intValue(X, -1);
      int y = blockEntity.intValue(Y, -1);
      int z = blockEntity.intValue(Z, -1);
      if (x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ) {
        blockEntities.put((y * sizeZ + z) * sizeX + x, blockEntity.without(POSITION_KEYS));
      }
    }
    return blockEntities;
  }
}
