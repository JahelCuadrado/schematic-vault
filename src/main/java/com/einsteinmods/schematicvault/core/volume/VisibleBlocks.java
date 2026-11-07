package com.einsteinmods.schematicvault.core.volume;

import java.util.Arrays;

/**
 * Which blocks of a volume can be seen: every non-air block except those enclosed on all six sides
 * by opaque full blocks (a block on the box's border always shows). The client says which palette
 * entries are opaque full blocks; the mesher then skips the hidden ones, whose faces it would cull
 * anyway. One pass over the cells, no allocation besides the result.
 *
 * <p>A cut ({@link #of(VoxelVolume, boolean[], LayerRange)}) keeps only a range of Y layers, as if
 * every cell outside it were air: its top and bottom layers are then borders, so their outer faces
 * show.
 */
public final class VisibleBlocks {
  private VisibleBlocks() {}

  /**
   * The cell indices of the visible blocks, ascending.
   *
   * @param opaqueByPaletteIndex whether each palette entry is an opaque full block
   */
  public static int[] of(VoxelVolume volume, boolean[] opaqueByPaletteIndex) {
    return of(volume, opaqueByPaletteIndex, LayerRange.ALL);
  }

  /**
   * The cell indices of the visible blocks of a range of Y layers, as if every cell outside it were
   * air; ascending.
   *
   * @param opaqueByPaletteIndex whether each palette entry is an opaque full block
   * @param range Y layers kept, cut to the volume's height (nothing when it starts above it)
   */
  public static int[] of(VoxelVolume volume, boolean[] opaqueByPaletteIndex, LayerRange range) {
    return of(volume, new boolean[][] {opaqueByPaletteIndex}, new int[volume.sizeY()], range);
  }

  /**
   * The cell indices of the visible blocks of a range of Y layers when a replacement rule gives
   * some layers their own palette; ascending.
   *
   * @param opaqueByBand whether each palette entry is an opaque full block, per palette band
   * @param bandOfY the band each Y layer reads
   * @param range Y layers kept, cut to the volume's height (nothing when it starts above it)
   */
  public static int[] of(
      VoxelVolume volume, boolean[][] opaqueByBand, int[] bandOfY, LayerRange range) {
    int sizeX = volume.sizeX();
    int bottom = range.bottom();
    int top = Math.min(range.top(), volume.sizeY() - 1);
    int keptLayers = Math.max(0, top - bottom + 1);
    int sizeZ = volume.sizeZ();
    int[] cells = volume.cells();
    int layer = sizeX * sizeZ;
    int nonAir = cells.length - volume.paletteCounts()[VoxelVolume.AIR];
    int[] visible = new int[Math.min(nonAir, keptLayers * layer)];
    int found = 0;
    int index = bottom * layer;
    for (int y = bottom; y <= top; y++) {
      boolean[] opaque = opaqueByBand[bandOfY[y]];
      // Only read off the layer's own band for inner layers: y == bottom and y == top are borders.
      boolean[] below = y > bottom ? opaqueByBand[bandOfY[y - 1]] : opaque;
      boolean[] above = y < top ? opaqueByBand[bandOfY[y + 1]] : opaque;
      for (int z = 0; z < sizeZ; z++) {
        for (int x = 0; x < sizeX; x++, index++) {
          if (cells[index] == VoxelVolume.AIR) {
            continue;
          }
          boolean border =
              x == 0 || y == bottom || z == 0 || x == sizeX - 1 || y == top || z == sizeZ - 1;
          if (border
              || !opaque[cells[index - 1]]
              || !opaque[cells[index + 1]]
              || !opaque[cells[index - sizeX]]
              || !opaque[cells[index + sizeX]]
              || !below[cells[index - layer]]
              || !above[cells[index + layer]]) {
            visible[found++] = index;
          }
        }
      }
    }
    return found == visible.length ? visible : Arrays.copyOf(visible, found);
  }
}
