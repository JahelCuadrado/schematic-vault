package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.StringTag;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads Sponge schematics ({@code .schem}, WorldEdit's format since 1.13), versions 1 to 3. The
 * palette maps block-state strings to indices; the cells are unsigned LEB128 varints, x fastest,
 * then z, then y. Version 3 nests everything under {@code Schematic} and {@code Blocks}, and keeps
 * a block entity's own NBT under {@code Data}; versions 1 and 2 keep it beside {@code Pos} and
 * {@code Id}.
 */
public final class SpongeReader {
  private static final int MIN_VERSION = 1;
  private static final int MAX_VERSION = 3;
  private static final int NESTED_VERSION = 3;

  /** Version 1 has no data version: it was written by 1.13 (data version 1519). */
  private static final int VERSION_ONE_DATA_VERSION = 1519;

  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
  private static final int VARINT_PAYLOAD = 0x7F;
  private static final int VARINT_CONTINUE = 0x80;
  private static final int VARINT_SHIFT = 7;

  /** A varint for an index below {@link VoxelVolume#MAX_PALETTE} has at most this many bytes. */
  private static final int MAX_VARINT_BYTES = 3;

  private static final String SCHEMATIC = "Schematic";
  private static final String VERSION = "Version";
  private static final String DATA_VERSION = "DataVersion";
  private static final String WIDTH = "Width";
  private static final String HEIGHT = "Height";
  private static final String LENGTH = "Length";
  private static final String BLOCKS = "Blocks";
  private static final String PALETTE = "Palette";
  private static final String DATA = "Data";
  private static final String BLOCK_DATA = "BlockData";
  private static final String BLOCK_ENTITIES = "BlockEntities";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String POSITION = "Pos";
  private static final String ID = "Id";
  private static final String VANILLA_ID = "id";
  private static final String MATERIALS = "Materials";
  private static final Set<String> PLACEMENT_KEYS = Set.of(POSITION, ID);
  private static final int AXES = 3;

  private SpongeReader() {}

  /** Whether {@code root} looks like a Sponge schematic (it has a palette of block states). */
  public static boolean isSponge(CompoundTag root) {
    CompoundTag schematic = root.compound(SCHEMATIC).orElse(root);
    return schematic.contains(PALETTE)
        || schematic.contains(BLOCK_DATA)
        || schematic.get(BLOCKS) instanceof CompoundTag;
  }

  public static VoxelVolume read(CompoundTag root) throws VolumeFormatException {
    CompoundTag schematic = root.compound(SCHEMATIC).orElse(root);
    if (schematic.contains(MATERIALS)) {
      throw VolumeFormatException.unreadable("an MCEdit schematic saved as .schem");
    }
    int version = schematic.intValue(VERSION, MIN_VERSION);
    if (version < MIN_VERSION || version > MAX_VERSION) {
      throw VolumeFormatException.unreadable("sponge version " + version);
    }
    boolean nested = version >= NESTED_VERSION;
    CompoundTag blocks = nested ? schematic.compound(BLOCKS).orElse(null) : schematic;
    if (blocks == null) {
      throw VolumeFormatException.unreadable("no blocks");
    }
    VolumeBuilder builder =
        VolumeBuilder.of(
            schematic.intValue(WIDTH, 0) & UNSIGNED_SHORT_MASK,
            schematic.intValue(HEIGHT, 0) & UNSIGNED_SHORT_MASK,
            schematic.intValue(LENGTH, 0) & UNSIGNED_SHORT_MASK);
    int[] shared = palette(builder, blocks.compound(PALETTE).orElse(null));
    byte[] data = blocks.byteArray(nested ? DATA : BLOCK_DATA);
    readCells(builder, shared, data == null ? new byte[0] : data);
    List<CompoundTag> blockEntities =
        blocks.contains(BLOCK_ENTITIES)
            ? blocks.compounds(BLOCK_ENTITIES)
            : schematic.compounds(TILE_ENTITIES);
    for (CompoundTag blockEntity : blockEntities) {
      addBlockEntity(builder, blockEntity, nested);
    }
    int dataVersion =
        schematic.intValue(DATA_VERSION, version == MIN_VERSION ? VERSION_ONE_DATA_VERSION : 0);
    return builder.build(dataVersion, dataVersion);
  }

  /** The shared palette index of each of the file's indices. */
  private static int[] palette(VolumeBuilder builder, @Nullable CompoundTag palette)
      throws VolumeFormatException {
    if (palette == null) {
      throw VolumeFormatException.unreadable("no palette");
    }
    Map<Integer, String> byIndex = new HashMap<>();
    int size = 0;
    for (Map.Entry<String, NbtTag> entry : palette.values().entrySet()) {
      int index = entry.getValue().number().map(Number::intValue).orElse(-1);
      if (index < 0 || index >= VoxelVolume.MAX_PALETTE) {
        throw VolumeFormatException.unreadable("bad palette index " + index);
      }
      byIndex.put(index, entry.getKey());
      size = Math.max(size, index + 1);
    }
    int[] shared = new int[size];
    for (Map.Entry<Integer, String> entry : byIndex.entrySet()) {
      shared[entry.getKey()] = builder.paletteIndex(entry.getValue());
    }
    return shared;
  }

  private static void readCells(VolumeBuilder builder, int[] shared, byte[] data)
      throws VolumeFormatException {
    long blocks = 0;
    int position = 0;
    for (int y = 0; y < builder.sizeY(); y++) {
      for (int z = 0; z < builder.sizeZ(); z++) {
        for (int x = 0; x < builder.sizeX(); x++) {
          int value = 0;
          int shift = 0;
          int read = 0;
          while (true) {
            if (position >= data.length) {
              throw VolumeFormatException.unreadable("block data shorter than the schematic");
            }
            int current = data[position++];
            value |= (current & VARINT_PAYLOAD) << shift;
            if ((current & VARINT_CONTINUE) == 0) {
              break;
            }
            shift += VARINT_SHIFT;
            if (++read >= MAX_VARINT_BYTES) {
              throw VolumeFormatException.unreadable("block index too long");
            }
          }
          if (value >= shared.length) {
            throw VolumeFormatException.unreadable("block state outside the palette");
          }
          int state = shared[value];
          if (state != VoxelVolume.AIR) {
            blocks++;
            builder.set(x, y, z, state);
          }
        }
      }
    }
    builder.countBlocks(blocks);
  }

  private static void addBlockEntity(VolumeBuilder builder, CompoundTag entry, boolean nested) {
    int[] position = entry.intArray(POSITION);
    if (position == null || position.length < AXES) {
      return;
    }
    CompoundTag nbt =
        nested
            ? entry.compound(DATA).orElse(new CompoundTag(Map.of()))
            : entry.without(PLACEMENT_KEYS);
    String id = entry.string(ID).orElse(null);
    if (id != null && !nbt.contains(VANILLA_ID)) {
      Map<String, NbtTag> withId = new LinkedHashMap<>(nbt.values());
      withId.put(VANILLA_ID, new StringTag(id));
      nbt = new CompoundTag(withId);
    }
    builder.blockEntity(position[0], position[1], position[2], nbt);
  }
}
