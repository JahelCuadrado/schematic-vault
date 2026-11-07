package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * A design as the block renderers see a level ({@link BlockAndTintGetter}, what vanilla's {@code
 * RenderSectionRegion} gives {@code SectionCompiler}): the volume's blocks at (0, 0, 0) and up, air
 * outside, every block at full light (sky and block 15, so nothing is dark inside a build), plains
 * tints (one biome everywhere) and the overworld's face shading. Each Y layer reads the palette of
 * its band, so a replacement limited to some layers shows without copying cells. A cut design (a
 * range of layers) is air outside its kept layers, so the faces of its top and bottom layers are
 * not culled. Read by the mesher's thread only.
 */
final class PreviewWorld implements BlockAndTintGetter {
  private static final int FULL_LIGHT = 15;
  private static final BlockState AIR = Blocks.AIR.defaultBlockState();

  private final VoxelVolume volume;
  private final BlockState[][] statesByBand;
  private final int[] bandOfY;
  private final Map<Integer, BlockEntity> blockEntities;
  private final @Nullable Biome biome;
  private final int height;
  private final int bottomLayer;
  private final int topLayer;

  /**
   * @param statesByBand the game's state of each palette index, per band of layers
   * @param bandOfY the band each Y layer reads
   * @param blockEntities by cell index
   * @param biome tints come from it; white when null
   * @param keptLayers Y layers kept ({@link LayerRange#ALL} for the whole design)
   */
  PreviewWorld(
      VoxelVolume volume,
      BlockState[][] statesByBand,
      int[] bandOfY,
      Map<Integer, BlockEntity> blockEntities,
      @Nullable Biome biome,
      LayerRange keptLayers) {
    this.volume = volume;
    this.bottomLayer = keptLayers.bottom();
    this.topLayer = Math.min(keptLayers.top(), volume.sizeY() - 1);
    this.statesByBand = statesByBand;
    this.bandOfY = bandOfY;
    this.blockEntities = blockEntities;
    this.biome = biome;
    // Whole sections, as a level's height always is.
    this.height =
        SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(volume.sizeY() - 1) + 1);
  }

  private int indexOf(BlockPos pos) {
    int x = pos.getX();
    int y = pos.getY();
    int z = pos.getZ();
    if (x < 0
        || y < bottomLayer
        || z < 0
        || x >= volume.sizeX()
        || y > topLayer
        || z >= volume.sizeZ()) {
      return -1;
    }
    return volume.index(x, y, z);
  }

  @Override
  public BlockState getBlockState(BlockPos pos) {
    int index = indexOf(pos);
    return index < 0 ? AIR : statesByBand[bandOfY[pos.getY()]][volume.cells()[index]];
  }

  @Override
  public FluidState getFluidState(BlockPos pos) {
    return getBlockState(pos).getFluidState();
  }

  @Override
  public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
    int index = indexOf(pos);
    return index < 0 ? null : blockEntities.get(index);
  }

  @Override
  public CardinalLighting cardinalLighting() {
    return CardinalLighting.DEFAULT;
  }

  @Override
  public LevelLightEngine getLightEngine() {
    return LevelLightEngine.EMPTY;
  }

  @Override
  public int getBrightness(LightLayer layer, BlockPos pos) {
    return FULL_LIGHT;
  }

  @Override
  public int getRawBrightness(BlockPos pos, int darkening) {
    return FULL_LIGHT;
  }

  @Override
  public int getBlockTint(BlockPos pos, ColorResolver resolver) {
    Biome tintBiome = biome;
    return tintBiome == null ? -1 : resolver.getColor(tintBiome, pos.getX(), pos.getZ());
  }

  @Override
  public int getHeight() {
    return height;
  }

  @Override
  public int getMinY() {
    return 0;
  }
}
