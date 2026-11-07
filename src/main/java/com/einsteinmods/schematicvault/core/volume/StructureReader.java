package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads vanilla structure files ({@code .nbt}, saved by structure blocks): a {@code size}, a
 * palette of {@code {Name, Properties}} (or several {@code palettes}, of which the first is shown)
 * and a list of {@code blocks}, each with its {@code pos}, palette {@code state} and optional
 * block-entity {@code nbt}. Positions where no block is listed are structure voids: left as air.
 */
public final class StructureReader {
  private static final String SIZE = "size";
  private static final String PALETTE = "palette";
  private static final String PALETTES = "palettes";
  private static final String BLOCKS = "blocks";
  private static final String POSITION = "pos";
  private static final String STATE = "state";
  private static final String NBT = "nbt";
  private static final String DATA_VERSION = "DataVersion";
  private static final int AXES = 3;

  private StructureReader() {}

  public static VoxelVolume read(CompoundTag root) throws VolumeFormatException {
    int[] size = ints(root.list(SIZE));
    if (size == null) {
      throw VolumeFormatException.unreadable("no size");
    }
    VolumeBuilder builder = VolumeBuilder.of(size[0], size[1], size[2]);
    List<CompoundTag> palette = root.compounds(PALETTE);
    if (palette.isEmpty()) {
      List<NbtTag> palettes = root.list(PALETTES);
      if (!palettes.isEmpty() && palettes.getFirst() instanceof ListTag first) {
        palette = first.compounds();
      }
    }
    int[] shared = new int[palette.size()];
    for (int index = 0; index < shared.length; index++) {
      shared[index] = builder.paletteIndex(BlockStateString.of(palette.get(index)));
    }
    long blocks = 0;
    for (CompoundTag block : root.compounds(BLOCKS)) {
      int state = block.intValue(STATE, -1);
      if (state < 0 || state >= shared.length) {
        throw VolumeFormatException.unreadable("block state outside the palette");
      }
      int[] position = ints(block.list(POSITION));
      if (shared[state] == VoxelVolume.AIR || position == null) {
        continue;
      }
      blocks++;
      builder.set(position[0], position[1], position[2], shared[state]);
      CompoundTag nbt = block.compound(NBT).orElse(null);
      if (nbt != null) {
        builder.blockEntity(position[0], position[1], position[2], nbt);
      }
    }
    builder.countBlocks(blocks);
    int dataVersion = root.intValue(DATA_VERSION, 0);
    return builder.build(dataVersion, dataVersion);
  }

  /** Three numbers of a list ({@code [x, y, z]}), or null. */
  private static int @Nullable [] ints(List<NbtTag> list) {
    if (list.size() != AXES) {
      return null;
    }
    int[] values = new int[AXES];
    for (int index = 0; index < AXES; index++) {
      Number number = list.get(index).number().orElse(null);
      if (number == null) {
        return null;
      }
      values[index] = number.intValue();
    }
    return values;
  }
}
