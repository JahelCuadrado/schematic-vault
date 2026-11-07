package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.volume.LayerOffsets;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.SortedMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A design's geometry built off the render thread, still in native memory: one mesh per render
 * layer (the translucent one already sorted for the first view), the block entities to draw with
 * their renderers, and the box the visible blocks fill. Quads and block entities are in block Y
 * order, bottom first ({@link LayerOffsets}), so a range of layers is one run of each. Uploaded
 * once ({@link PreviewScene#upload}) and closed right after, which frees its memory; a mesh nobody
 * uploads must be closed too.
 *
 * @param minX the visible blocks' box, in block units of the volume
 * @param standIns blocks the game does not know, by id, with the vanilla block drawn for each
 *     ({@link PreviewPalette#standIns})
 * @param quads quads in all layers
 * @param height the volume's Y layers
 */
record PreviewMesh(
    List<Layer> layers,
    List<ByteBufferBuilder> buffers,
    List<BlockEntity> blockEntities,
    float minX,
    float minY,
    float minZ,
    float maxX,
    float maxY,
    float maxZ,
    SortedMap<String, Block> standIns,
    long quads,
    int height)
    implements AutoCloseable {
  /**
   * A render layer's quads; {@code mesh} holds a sorted index buffer for translucent quads.
   *
   * @param offsets where each Y layer's quads start
   * @param layerIndices translucent quads of the whole design only: the indices sorted inside each
   *     Y layer, layers bottom first, so a run of them draws a range of layers (owned by the mesh's
   *     buffers); null otherwise
   */
  record Layer(
      ChunkSectionLayer layer,
      MeshData mesh,
      LayerOffsets offsets,
      @Nullable ByteBuffer layerIndices) {}

  PreviewMesh {
    layers = List.copyOf(layers);
    buffers = List.copyOf(buffers);
    blockEntities = List.copyOf(blockEntities);
  }

  /** Whether the design has nothing to draw (all air). */
  boolean isEmpty() {
    return layers.isEmpty() && blockEntities.isEmpty();
  }

  @Override
  public void close() {
    for (Layer layer : layers) {
      layer.mesh().close();
    }
    for (ByteBufferBuilder buffer : buffers) {
      buffer.close();
    }
  }
}
