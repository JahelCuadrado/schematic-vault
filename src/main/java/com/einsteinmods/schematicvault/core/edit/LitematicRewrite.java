package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LitematicReader;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Changes the palettes of a {@code .litematic}: each region's {@code BlockStatePalette} (entries
 * that become equal merged, its {@code BlockStates} repacked for the new palette size), its {@code
 * TileEntities} without the data of blocks that changed type, and {@code Metadata.TotalBlocks}
 * counted again (Litematica shows it as the design's block count). Everything else (entities,
 * pending ticks, the other metadata) is kept as it is.
 *
 * <p>Changes limited to some layers ({@link #rewriteLayers}) keep each region's palette and add the
 * new states after it ({@link BandPalette}), then rewrite its cells. A region's cell belongs to the
 * layer its place in the design gives ({@link LitematicReader.Region}). Where regions overlap, the
 * reader lets a later region's block win and its air show what lies under it, so a block removed
 * from a later region also clears the cells under it: the file then reads as the 3D shows.
 */
final class LitematicRewrite {
  private static final String METADATA = "Metadata";
  private static final String TOTAL_BLOCKS = "TotalBlocks";
  private static final String REGIONS = "Regions";
  private static final String SIZE = "Size";
  private static final String PALETTE = "BlockStatePalette";
  private static final String BLOCK_STATES = "BlockStates";
  private static final String TILE_ENTITIES = "TileEntities";
  private static final String X = "x";
  private static final String Y = "y";
  private static final String Z = "z";

  private LitematicRewrite() {}

  static CompoundTag rewrite(CompoundTag root, StateMapper mapper) throws VolumeFormatException {
    CompoundTag regions = root.compound(REGIONS).orElse(null);
    if (regions == null) {
      throw VolumeFormatException.unreadable("no regions");
    }
    Map<String, NbtTag> rewritten = new LinkedHashMap<>();
    long totalBlocks = 0;
    for (Map.Entry<String, NbtTag> entry : regions.values().entrySet()) {
      if (entry.getValue() instanceof CompoundTag region) {
        Region result = region(region, mapper);
        rewritten.put(entry.getKey(), result.nbt());
        totalBlocks += result.blocks();
      } else {
        rewritten.put(entry.getKey(), entry.getValue());
      }
    }
    return withRegions(root, rewritten, totalBlocks);
  }

  private static CompoundTag withRegions(
      CompoundTag root, Map<String, NbtTag> regions, long totalBlocks) {
    CompoundTag result = root.with(REGIONS, new CompoundTag(regions));
    CompoundTag metadata = root.compound(METADATA).orElse(null);
    if (metadata != null) {
      result =
          result.with(
              METADATA,
              metadata.with(
                  TOTAL_BLOCKS, new IntTag((int) Math.min(Integer.MAX_VALUE, totalBlocks))));
    }
    return result;
  }

  static CompoundTag rewriteLayers(CompoundTag root, LayerChanges changes)
      throws VolumeFormatException {
    CompoundTag regions = root.compound(REGIONS).orElse(null);
    if (regions == null) {
      throw VolumeFormatException.unreadable("no regions");
    }
    long minY = Long.MAX_VALUE;
    for (NbtTag entry : regions.values().values()) {
      if (entry instanceof CompoundTag region) {
        minY = Math.min(minY, LitematicReader.Region.of(region).originY());
      }
    }
    Map<String, LayerRegion> edited = new LinkedHashMap<>();
    for (Map.Entry<String, NbtTag> entry : regions.values().entrySet()) {
      if (entry.getValue() instanceof CompoundTag region) {
        LayerRegion layerRegion = LayerRegion.of(LitematicReader.Region.of(region), minY, changes);
        if (layerRegion != null) {
          edited.put(entry.getKey(), layerRegion);
        }
      }
    }
    List<LayerRegion> inOrder = List.copyOf(edited.values());
    for (int later = 1; later < inOrder.size(); later++) {
      for (int earlier = 0; earlier < later; earlier++) {
        inOrder.get(later).clearUnderRemoved(inOrder.get(earlier));
      }
    }
    if (inOrder.stream().noneMatch(LayerRegion::changed)) {
      return root;
    }
    Map<String, NbtTag> rewritten = new LinkedHashMap<>(regions.values());
    long totalBlocks = 0;
    for (Map.Entry<String, LayerRegion> entry : edited.entrySet()) {
      LayerRegion region = entry.getValue();
      rewritten.put(entry.getKey(), region.nbt());
      totalBlocks += region.blocks();
    }
    return withRegions(root, rewritten, totalBlocks);
  }

  /** A region's cells while their layers change; the result comes out of {@link #nbt}. */
  private static final class LayerRegion {
    private final LitematicReader.Region box;
    private final int offsetY;
    private final List<CompoundTag> palette;
    private final int[] oldCells;
    private final int[] cells;
    private final BandPalette bands;
    private final LayerChanges changes;
    private boolean changed;

    private LayerRegion(
        LitematicReader.Region box,
        int offsetY,
        List<CompoundTag> palette,
        int[] oldCells,
        BandPalette bands,
        LayerChanges changes) {
      this.box = box;
      this.offsetY = offsetY;
      this.palette = palette;
      this.oldCells = oldCells;
      this.cells = new int[oldCells.length];
      this.bands = bands;
      this.changes = changes;
    }

    /** The region with its layers changed; null when it holds no cells (it stays as it is). */
    static @Nullable LayerRegion of(LitematicReader.Region box, long minY, LayerChanges changes)
        throws VolumeFormatException {
      long volume = (long) box.sizeX() * box.sizeY() * box.sizeZ();
      if (volume > VoxelVolume.MAX_CELLS) {
        throw VolumeFormatException.tooLarge(
            "region " + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ());
      }
      List<CompoundTag> palette = box.nbt().compounds(PALETTE);
      long[] packed = box.nbt().longArray(BLOCK_STATES);
      if (palette.isEmpty() || volume == 0 || packed == null) {
        return null;
      }
      List<@Nullable String> states = new ArrayList<>(palette.size());
      for (CompoundTag entry : palette) {
        states.add(BlockStateString.of(entry));
      }
      int[] oldCells = PackedStates.unpack(packed, (int) volume, palette.size());
      LayerRegion region =
          new LayerRegion(
              box,
              Math.toIntExact(box.originY() - minY),
              palette,
              oldCells,
              new BandPalette(List.of(states), changes),
              changes);
      region.applyChanges();
      return region;
    }

    private int layerOf(int y) {
      return changes.bandOfY()[offsetY + y];
    }

    private void applyChanges() {
      int layerCells = box.sizeX() * box.sizeZ();
      for (int y = 0; y < box.sizeY(); y++) {
        int band = layerOf(y);
        int end = (y + 1) * layerCells;
        for (int index = y * layerCells; index < end; index++) {
          int old = oldCells[index];
          int cell = bands.newIndex(band, old);
          cells[index] = cell;
          changed |= cell != old;
        }
      }
    }

    boolean changed() {
      return changed;
    }

    private int index(int x, int y, int z) {
      return (y * box.sizeZ() + z) * box.sizeX() + x;
    }

    private boolean removed(int index) {
      return bands.isAir(cells[index]) && !bands.isAir(oldCells[index]);
    }

    /** Clears the cells of {@code under}, an earlier region, below the blocks this one removed. */
    void clearUnderRemoved(LayerRegion under) {
      if (!changed) {
        return;
      }
      long fromX = Math.max(box.originX(), under.box.originX());
      long fromY = Math.max(box.originY(), under.box.originY());
      long fromZ = Math.max(box.originZ(), under.box.originZ());
      long toX = Math.min(box.originX() + box.sizeX(), under.box.originX() + under.box.sizeX());
      long toY = Math.min(box.originY() + box.sizeY(), under.box.originY() + under.box.sizeY());
      long toZ = Math.min(box.originZ() + box.sizeZ(), under.box.originZ() + under.box.sizeZ());
      for (long y = fromY; y < toY; y++) {
        for (long z = fromZ; z < toZ; z++) {
          for (long x = fromX; x < toX; x++) {
            int index =
                index(
                    (int) (x - box.originX()),
                    (int) (y - box.originY()),
                    (int) (z - box.originZ()));
            int underIndex =
                under.index(
                    (int) (x - under.box.originX()),
                    (int) (y - under.box.originY()),
                    (int) (z - under.box.originZ()));
            if (removed(index) && !under.bands.isAir(under.cells[underIndex])) {
              under.cells[underIndex] = under.bands.airIndex();
              under.changed = true;
            }
          }
        }
      }
    }

    private long blocks() {
      long blocks = 0;
      for (int cell : cells) {
        blocks += bands.isAir(cell) ? 0 : 1;
      }
      return blocks;
    }

    private CompoundTag nbt() {
      CompoundTag region = box.nbt();
      if (!changed) {
        return region;
      }
      List<NbtTag> newPalette = new ArrayList<>(palette);
      for (int index = palette.size(); index < bands.size(); index++) {
        newPalette.add(BlockStateString.compound(bands.state(0, index)));
      }
      CompoundTag result =
          region
              .with(PALETTE, new ListTag(newPalette))
              .with(BLOCK_STATES, new LongArrayTag(PackedStates.pack(cells, bands.size())));
      return region.contains(TILE_ENTITIES)
          ? result.with(TILE_ENTITIES, new ListTag(blockEntities()))
          : result;
    }

    private List<NbtTag> blockEntities() {
      List<NbtTag> kept = new ArrayList<>();
      for (CompoundTag blockEntity : box.nbt().compounds(TILE_ENTITIES)) {
        int x = blockEntity.intValue(X, -1);
        int y = blockEntity.intValue(Y, -1);
        int z = blockEntity.intValue(Z, -1);
        boolean inside =
            x >= 0 && y >= 0 && z >= 0 && x < box.sizeX() && y < box.sizeY() && z < box.sizeZ();
        if (!inside) {
          kept.add(blockEntity);
          continue;
        }
        int index = index(x, y, z);
        if (!removed(index) && !bands.dropsBlockEntity(layerOf(y), oldCells[index])) {
          kept.add(blockEntity);
        }
      }
      return kept;
    }
  }

  /** A region after its changes and its non-air cells. */
  private record Region(CompoundTag nbt, long blocks) {}

  private static Region region(CompoundTag region, StateMapper mapper)
      throws VolumeFormatException {
    CompoundTag size = region.compound(SIZE).orElse(null);
    if (size == null) {
      throw VolumeFormatException.unreadable("region without size");
    }
    long sizeX = Math.abs((long) size.intValue(X, 0));
    long sizeY = Math.abs((long) size.intValue(Y, 0));
    long sizeZ = Math.abs((long) size.intValue(Z, 0));
    long volume = sizeX * sizeY * sizeZ;
    if (volume > VoxelVolume.MAX_CELLS) {
      throw VolumeFormatException.tooLarge("region " + sizeX + "x" + sizeY + "x" + sizeZ);
    }
    List<CompoundTag> palette = region.compounds(PALETTE);
    long[] packed = region.longArray(BLOCK_STATES);
    if (palette.isEmpty() || volume == 0 || packed == null) {
      return new Region(region, 0);
    }
    int count = palette.size();
    List<String> keys = new ArrayList<>(count);
    @Nullable StateChange[] changes = new StateChange[count];
    boolean changed = false;
    for (int index = 0; index < count; index++) {
      String state = BlockStateString.of(palette.get(index));
      StateChange change = mapper.change(state);
      changes[index] = change;
      changed |= change != null;
      keys.add(change == null ? state : change.state());
    }
    int[] cells = PackedStates.unpack(packed, (int) volume, count);
    PaletteMerge merge = PaletteMerge.of(keys);
    boolean[] air = new boolean[merge.size()];
    for (int index = 0; index < air.length; index++) {
      air[index] = BlockStateString.isAir(keys.get(merge.kept().get(index)));
    }
    if (!changed && merge.size() == count) {
      return new Region(region, nonAir(cells, merge.oldToNew(), air));
    }
    List<NbtTag> blockEntities = new ArrayList<>();
    for (CompoundTag blockEntity : region.compounds(TILE_ENTITIES)) {
      int x = blockEntity.intValue(X, -1);
      int y = blockEntity.intValue(Y, -1);
      int z = blockEntity.intValue(Z, -1);
      boolean inside = x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
      StateChange change = inside ? changes[cells[(int) ((y * sizeZ + z) * sizeX + x)]] : null;
      if (change == null || change.keepBlockEntity()) {
        blockEntities.add(blockEntity);
      }
    }
    long blocks = nonAir(cells, merge.oldToNew(), air);
    int[] oldToNew = merge.oldToNew();
    for (int index = 0; index < cells.length; index++) {
      cells[index] = oldToNew[cells[index]];
    }
    List<NbtTag> newPalette = new ArrayList<>(merge.size());
    for (int old : merge.kept()) {
      StateChange change = changes[old];
      newPalette.add(change == null ? palette.get(old) : BlockStateString.compound(change.state()));
    }
    CompoundTag result =
        region
            .with(PALETTE, new ListTag(newPalette))
            .with(BLOCK_STATES, new LongArrayTag(PackedStates.pack(cells, merge.size())));
    if (region.contains(TILE_ENTITIES)) {
      result = result.with(TILE_ENTITIES, new ListTag(blockEntities));
    }
    return new Region(result, blocks);
  }

  private static long nonAir(int[] cells, int[] oldToNew, boolean[] air) {
    long blocks = 0;
    for (int cell : cells) {
      if (!air[oldToNew[cell]]) {
        blocks++;
      }
    }
    return blocks;
  }
}
