package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Changes the palette of a Sponge schematic ({@code .schem}, versions 1 to 3, laid out as {@code
 * SpongeReader} reads it): the {@code Palette} (its keys are states, so entries that become equal
 * must merge), the varint block data re-encoded for the merged indices, {@code PaletteMax}, and the
 * block entities without the data of blocks that changed type. Everything else is kept. Changes
 * limited to some layers ({@link #rewriteLayers}) keep the palette and add the new states after it
 * ({@link BandPalette}), then re-encode the cells.
 */
final class SpongeRewrite {
  private static final int NESTED_VERSION = 3;
  private static final int MIN_VERSION = 1;
  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
  private static final int VARINT_PAYLOAD = 0x7F;
  private static final int VARINT_CONTINUE = 0x80;
  private static final int VARINT_SHIFT = 7;
  private static final int MAX_VARINT_BYTES = 3;
  private static final int AXES = 3;

  private static final String SCHEMATIC = "Schematic";
  private static final String VERSION = "Version";
  private static final String WIDTH = "Width";
  private static final String HEIGHT = "Height";
  private static final String LENGTH = "Length";
  private static final String BLOCKS = "Blocks";
  private static final String PALETTE = "Palette";
  private static final String PALETTE_MAX = "PaletteMax";
  private static final String DATA = "Data";
  private static final String BLOCK_DATA = "BlockData";
  private static final String BLOCK_ENTITIES = "BlockEntities";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String POSITION = "Pos";

  private SpongeRewrite() {}

  static CompoundTag rewrite(CompoundTag root, StateMapper mapper) throws VolumeFormatException {
    Source source = Source.of(root);
    String[] states = states(source.palette());
    List<String> keys = new ArrayList<>(states.length);
    @Nullable StateChange[] changes = new StateChange[states.length];
    boolean changed = false;
    for (int index = 0; index < states.length; index++) {
      StateChange change = mapper.change(states[index]);
      changes[index] = change;
      changed |= change != null;
      keys.add(change == null ? states[index] : change.state());
    }
    if (!changed) {
      return root;
    }
    int[] cells = decode(source.data(), source.volume(), states.length);
    PaletteMerge merge = PaletteMerge.of(keys);

    List<NbtTag> blockEntities = new ArrayList<>();
    for (CompoundTag blockEntity : source.blockEntities()) {
      int cell = source.cellOf(blockEntity);
      StateChange change = cell < 0 ? null : changes[cells[cell]];
      if (change == null || change.keepBlockEntity()) {
        blockEntities.add(blockEntity);
      }
    }

    Map<String, NbtTag> newPalette = new LinkedHashMap<>();
    for (int index = 0; index < merge.size(); index++) {
      newPalette.put(keys.get(merge.kept().get(index)), new IntTag(index));
    }
    int[] oldToNew = merge.oldToNew();
    for (int index = 0; index < cells.length; index++) {
      cells[index] = oldToNew[cells[index]];
    }
    return source.with(newPalette, cells, blockEntities, merge.size());
  }

  /** Adds the new states after the palette and rewrites the cells of the layers that change. */
  static CompoundTag rewriteLayers(CompoundTag root, LayerChanges changes)
      throws VolumeFormatException {
    Source source = Source.of(root);
    String[] states = states(source.palette());
    BitSet indices = new BitSet(states.length);
    source
        .palette()
        .values()
        .values()
        .forEach(index -> indices.set(index.number().map(Number::intValue).orElse(0)));
    List<@Nullable String> listed = new ArrayList<>(Arrays.asList(states));
    for (int index = indices.nextClearBit(0);
        index < states.length;
        index = indices.nextClearBit(index + 1)) {
      listed.set(index, null);
    }
    BandPalette bands = new BandPalette(List.of(listed), changes);
    int[] oldCells = decode(source.data(), source.volume(), states.length);
    int[] cells = new int[oldCells.length];
    int layerCells = source.sizeX() * source.sizeZ();
    boolean changed = false;
    for (int y = 0; y < source.sizeY(); y++) {
      int band = changes.bandOfY()[y];
      int end = (y + 1) * layerCells;
      for (int index = y * layerCells; index < end; index++) {
        cells[index] = bands.newIndex(band, oldCells[index]);
        changed |= cells[index] != oldCells[index];
      }
    }
    if (!changed) {
      return root;
    }
    List<NbtTag> blockEntities = new ArrayList<>();
    for (CompoundTag blockEntity : source.blockEntities()) {
      int cell = source.cellOf(blockEntity);
      if (cell < 0
          || !bands.dropsBlockEntity(changes.bandOfY()[cell / layerCells], oldCells[cell])) {
        blockEntities.add(blockEntity);
      }
    }
    Map<String, NbtTag> newPalette = new LinkedHashMap<>(source.palette().values());
    for (int index = states.length; index < bands.size(); index++) {
      newPalette.put(bands.state(0, index), new IntTag(index));
    }
    return source.with(newPalette, cells, blockEntities, bands.size());
  }

  /**
   * Where a file keeps what a rewrite touches.
   *
   * @param wrapped whether everything hangs under {@code Schematic}
   * @param nested whether the blocks hang under {@code Blocks} (version 3)
   * @param blocks the compound holding the palette and the cells
   * @param dataKey the cells' key in {@code blocks}
   * @param blockEntityKey the block entities' key in {@code blockEntityHolder}
   */
  private record Source(
      CompoundTag root,
      boolean wrapped,
      CompoundTag schematic,
      boolean nested,
      CompoundTag blocks,
      CompoundTag palette,
      String dataKey,
      String blockEntityKey,
      CompoundTag blockEntityHolder,
      int sizeX,
      int sizeY,
      int sizeZ) {
    static Source of(CompoundTag root) throws VolumeFormatException {
      CompoundTag schematic = root.compound(SCHEMATIC).orElse(root);
      boolean nested = schematic.intValue(VERSION, MIN_VERSION) >= NESTED_VERSION;
      CompoundTag blocks = nested ? schematic.compound(BLOCKS).orElse(null) : schematic;
      CompoundTag palette = blocks == null ? null : blocks.compound(PALETTE).orElse(null);
      if (blocks == null || palette == null) {
        throw VolumeFormatException.unreadable("no palette");
      }
      int sizeX = schematic.intValue(WIDTH, 0) & UNSIGNED_SHORT_MASK;
      int sizeY = schematic.intValue(HEIGHT, 0) & UNSIGNED_SHORT_MASK;
      int sizeZ = schematic.intValue(LENGTH, 0) & UNSIGNED_SHORT_MASK;
      long volume = (long) sizeX * sizeY * sizeZ;
      if (volume > VoxelVolume.MAX_CELLS) {
        throw VolumeFormatException.tooLarge("box " + sizeX + "x" + sizeY + "x" + sizeZ);
      }
      boolean inBlocks = blocks.contains(BLOCK_ENTITIES);
      return new Source(
          root,
          root.get(SCHEMATIC) instanceof CompoundTag,
          schematic,
          nested,
          blocks,
          palette,
          nested ? DATA : BLOCK_DATA,
          inBlocks ? BLOCK_ENTITIES : TILE_ENTITIES,
          inBlocks ? blocks : schematic,
          sizeX,
          sizeY,
          sizeZ);
    }

    int volume() {
      return sizeX * sizeY * sizeZ;
    }

    byte[] data() {
      byte[] data = blocks.byteArray(dataKey);
      return data == null ? new byte[0] : data;
    }

    List<CompoundTag> blockEntities() {
      return blockEntityHolder.compounds(blockEntityKey);
    }

    /** The cell of a block entity; -1 when it has no position inside the box. */
    int cellOf(CompoundTag blockEntity) {
      int[] position = blockEntity.intArray(POSITION);
      if (position == null
          || position.length < AXES
          || position[0] < 0
          || position[1] < 0
          || position[2] < 0
          || position[0] >= sizeX
          || position[1] >= sizeY
          || position[2] >= sizeZ) {
        return -1;
      }
      return (position[1] * sizeZ + position[2]) * sizeX + position[0];
    }

    /** The tree with a new palette, its cells and block entities; the rest as it was. */
    CompoundTag with(
        Map<String, NbtTag> newPalette, int[] cells, List<NbtTag> blockEntities, int paletteMax) {
      CompoundTag newBlocks =
          blocks
              .with(PALETTE, new CompoundTag(newPalette))
              .with(dataKey, new ByteArrayTag(encode(cells)));
      if (blockEntityHolder == blocks && blocks.contains(blockEntityKey)) {
        newBlocks = newBlocks.with(blockEntityKey, new ListTag(blockEntities));
      }
      CompoundTag newSchematic = nested ? schematic.with(BLOCKS, newBlocks) : newBlocks;
      if (blockEntityHolder == schematic && schematic.contains(blockEntityKey)) {
        newSchematic = newSchematic.with(blockEntityKey, new ListTag(blockEntities));
      }
      if (newSchematic.contains(PALETTE_MAX)) {
        newSchematic = newSchematic.with(PALETTE_MAX, new IntTag(paletteMax));
      }
      return wrapped ? root.with(SCHEMATIC, newSchematic) : newSchematic;
    }
  }

  /**
   * The state of each palette index; an index the palette skips is air (as the reader reads it).
   */
  private static String[] states(CompoundTag palette) throws VolumeFormatException {
    int size = 0;
    for (NbtTag value : palette.values().values()) {
      int index = value.number().map(Number::intValue).orElse(-1);
      if (index < 0 || index >= VoxelVolume.MAX_PALETTE) {
        throw VolumeFormatException.unreadable("bad palette index " + index);
      }
      size = Math.max(size, index + 1);
    }
    String[] states = new String[size];
    for (int index = 0; index < size; index++) {
      states[index] = VoxelVolume.AIR_STATE;
    }
    for (Map.Entry<String, NbtTag> entry : palette.values().entrySet()) {
      states[entry.getValue().number().map(Number::intValue).orElse(0)] = entry.getKey();
    }
    return states;
  }

  private static int[] decode(byte[] data, int volume, int paletteSize)
      throws VolumeFormatException {
    int[] cells = new int[volume];
    int position = 0;
    for (int cell = 0; cell < volume; cell++) {
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
      if (value >= paletteSize) {
        throw VolumeFormatException.unreadable("block state outside the palette");
      }
      cells[cell] = value;
    }
    return cells;
  }

  private static byte[] encode(int[] cells) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(cells.length);
    for (int cell : cells) {
      int value = cell;
      while ((value & ~VARINT_PAYLOAD) != 0) {
        bytes.write((value & VARINT_PAYLOAD) | VARINT_CONTINUE);
        value >>>= VARINT_SHIFT;
      }
      bytes.write(value);
    }
    return bytes.toByteArray();
  }
}
