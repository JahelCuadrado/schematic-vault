package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.volume.LayerOffsets;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A design ready to draw: its mesh in GPU buffers (uploaded once), its block entities' render
 * states (extracted once: nothing in a preview moves), and the texture the preview is drawn into,
 * redrawn only when the view or the size changed. Owns every GPU resource of one preview: {@link
 * #close} frees them all. Render thread.
 *
 * <p>Layers ({@link #showLayers}): the whole design's geometry is in block Y order, so a range of
 * layers draws one run of it at once (no rebuild); its top and bottom layers then show holes where
 * the blocks left out hid faces. A cut geometry, built on the worker with the cells outside the
 * range treated as air ({@link #attachCut}), is drawn instead only while the range it was built for
 * is the one shown. Both share this scene's framing, so a cut never moves the view.
 */
final class PreviewScene implements AutoCloseable {
  private static final Supplier<String> LABEL = () -> "Schematic Vault preview";
  private static final int COLOR_USAGE =
      GpuTexture.USAGE_COPY_DST
          | GpuTexture.USAGE_COPY_SRC
          | GpuTexture.USAGE_TEXTURE_BINDING
          | GpuTexture.USAGE_RENDER_ATTACHMENT;
  private static final int DEPTH_USAGE =
      GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
  private static final int COLOR_BYTES_PER_PIXEL = 4;
  private static final int DEPTH_BYTES_PER_PIXEL = 4;
  private static final int VERTICES_PER_QUAD = 4;

  /** Where block entities think the camera is: their distance checks never cull a preview. */
  private static final Vec3 CAMERA = Vec3.ZERO;

  /**
   * One render layer's quads on the GPU; {@code indices} is the sorted index buffer of the
   * translucent layer, null for layers drawn with the shared quad indices.
   *
   * @param offsets where each Y layer's quads start
   * @param layerIndices the translucent quads sorted inside each Y layer (whole design only), for a
   *     range of layers; null otherwise
   */
  record Layer(
      ChunkSectionLayer layer,
      GpuBuffer vertices,
      GpuBufferSlice vertexSlice,
      @Nullable GpuBuffer indices,
      @Nullable GpuBuffer layerIndices,
      IndexType indexType,
      int quads,
      LayerOffsets offsets) {}

  /**
   * A mesh on the GPU with its block entities' render states, both in block Y order.
   *
   * @param blockEntityOffsets where each Y layer's block entities start
   * @param bytes GPU memory of its buffers
   */
  record Geometry(
      List<Layer> layers,
      List<BlockEntityRenderState> blockEntities,
      LayerOffsets blockEntityOffsets,
      long bytes)
      implements AutoCloseable {
    Geometry {
      layers = List.copyOf(layers);
      blockEntities = List.copyOf(blockEntities);
    }

    @Override
    public void close() {
      layers.forEach(PreviewScene::close);
    }
  }

  private final Geometry full;
  private final int height;
  private final float centerX;
  private final float centerY;
  private final float centerZ;
  private final float sizeX;
  private final float sizeY;
  private final float sizeZ;

  /** The geometry built for {@link #cutLayers}, or null. */
  private @Nullable Geometry cut;

  private @Nullable LayerRange cutLayers;

  /** The Y layers shown, inside {@link #height}. */
  private LayerRange shownLayers;

  /** Bumped when what is drawn changes (layers shown, cut geometry): the texture is redrawn. */
  private int contentVersion;

  private int drawnContentVersion = -1;

  private @Nullable GpuTexture color;
  private @Nullable GpuTextureView colorView;
  private @Nullable GpuTexture depth;
  private @Nullable GpuTextureView depthView;
  private @Nullable TextureSetup textureSetup;
  private int drawnVersion = -1;

  private PreviewScene(Geometry full, PreviewMesh mesh) {
    this.full = full;
    this.height = mesh.height();
    this.shownLayers = LayerRange.ALL.clampedTo(mesh.height());
    this.centerX = (mesh.minX() + mesh.maxX()) / 2;
    this.centerY = (mesh.minY() + mesh.maxY()) / 2;
    this.centerZ = (mesh.minZ() + mesh.maxZ()) / 2;
    this.sizeX = mesh.maxX() - mesh.minX();
    this.sizeY = mesh.maxY() - mesh.minY();
    this.sizeZ = mesh.maxZ() - mesh.minZ();
  }

  /**
   * Uploads {@code mesh} (the whole design) to the GPU and extracts its block entities' render
   * states, then frees the mesh. Render thread.
   */
  static PreviewScene upload(Minecraft minecraft, PreviewMesh mesh) {
    // Only the mesh's box and height are read after it is freed.
    return new PreviewScene(uploadGeometry(minecraft, mesh), mesh);
  }

  /**
   * Uploads {@code mesh} and extracts its block entities' render states, then frees the mesh.
   * Render thread.
   */
  static Geometry uploadGeometry(Minecraft minecraft, PreviewMesh mesh) {
    GpuDevice device = RenderSystem.getDevice();
    List<Layer> layers = new ArrayList<>();
    long bytes = 0;
    try (mesh) {
      for (PreviewMesh.Layer layer : mesh.layers()) {
        MeshData data = layer.mesh();
        ByteBuffer vertexData = data.vertexBuffer();
        GpuBuffer vertices = device.createBuffer(LABEL, GpuBuffer.USAGE_VERTEX, vertexData);
        bytes += vertexData.remaining();
        ByteBuffer indexData = data.indexBuffer();
        GpuBuffer indices = null;
        if (indexData != null) {
          bytes += indexData.remaining();
          indices = device.createBuffer(LABEL, GpuBuffer.USAGE_INDEX, indexData);
        }
        ByteBuffer layerData = layer.layerIndices();
        GpuBuffer layerIndices = null;
        if (layerData != null) {
          bytes += layerData.remaining();
          layerIndices = device.createBuffer(LABEL, GpuBuffer.USAGE_INDEX, layerData);
        }
        layers.add(
            new Layer(
                layer.layer(),
                vertices,
                vertices.slice(),
                indices,
                layerIndices,
                data.drawState().indexType(),
                data.drawState().vertexCount() / VERTICES_PER_QUAD,
                layer.offsets()));
      }
      List<BlockEntityRenderState> states = new ArrayList<>();
      LayerOffsets.Builder offsets = LayerOffsets.builder(mesh.height());
      BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
      ClientLevel level = minecraft.level;
      // The mesh lists them in cell order, so their Y never goes down.
      for (BlockEntity blockEntity : mesh.blockEntities()) {
        BlockEntityRenderState state = extract(dispatcher, blockEntity, level);
        if (state != null) {
          offsets.enter(state.blockPos.getY(), states.size());
          states.add(state);
        }
      }
      return new Geometry(layers, states, offsets.finish(states.size()), bytes);
    } catch (RuntimeException exception) {
      layers.forEach(PreviewScene::close);
      throw exception;
    }
  }

  /**
   * A block entity's render state, as {@code BlockEntityRenderDispatcher#tryExtractRenderState}
   * makes it but without its checks of distance to the player: the block entity is lent the
   * client's level (renderers read their facing and neighbours through it; chests ignore their
   * block state without one), and is drawn at full light. Null when its renderer fails on it.
   */
  private static <E extends BlockEntity> @Nullable BlockEntityRenderState extract(
      BlockEntityRenderDispatcher dispatcher, E blockEntity, @Nullable ClientLevel level) {
    BlockEntityRenderer<E, BlockEntityRenderState> renderer = dispatcher.getRenderer(blockEntity);
    if (renderer == null || !blockEntity.getType().isValid(blockEntity.getBlockState())) {
      return null;
    }
    if (level != null) {
      blockEntity.setLevel(level);
    }
    try {
      BlockEntityRenderState state = renderer.createRenderState();
      renderer.extractRenderState(blockEntity, state, 1.0F, CAMERA, null);
      state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
      return state;
    } catch (RuntimeException exception) {
      // A renderer that needs a real world around its block: the preview leaves it out.
      return null;
    }
  }

  /** What to draw: the cut geometry when built for exactly the layers shown, else the whole one. */
  Geometry geometry() {
    Geometry built = cut;
    return built != null && shownLayers.equals(cutLayers) ? built : full;
  }

  /** The design's Y layers. */
  int height() {
    return height;
  }

  /** The Y layers shown, inside {@link #height}. */
  LayerRange shownLayers() {
    return shownLayers;
  }

  /**
   * Shows {@code layers} (clamped to {@link #height}): the next frame draws their run of the
   * geometry, or the cut geometry if it was built for them. No allocation unless clamped.
   */
  void showLayers(LayerRange layers) {
    LayerRange clamped = layers.clampedTo(height);
    if (!clamped.equals(shownLayers)) {
      shownLayers = clamped;
      contentVersion++;
    }
  }

  /** Whether a cut geometry is held for {@code layers}. */
  boolean hasCutFor(LayerRange layers) {
    return cut != null && layers.equals(cutLayers);
  }

  /** Keeps {@code built}, meshed for {@code layers} alone, in place of an older cut. */
  void attachCut(Geometry built, LayerRange layers) {
    releaseCut();
    cut = built;
    cutLayers = layers;
    contentVersion++;
  }

  /** Frees the cut geometry. */
  void releaseCut() {
    Geometry built = cut;
    if (built != null) {
      built.close();
      cut = null;
      contentVersion++;
    }
  }

  float centerX() {
    return centerX;
  }

  float centerY() {
    return centerY;
  }

  float centerZ() {
    return centerZ;
  }

  float sizeX() {
    return sizeX;
  }

  float sizeY() {
    return sizeY;
  }

  float sizeZ() {
    return sizeZ;
  }

  /** Whether the texture must be drawn again: new, resized, the view moved or the layers. */
  boolean needsDraw(int width, int height, int cameraVersion) {
    GpuTexture current = color;
    return current == null
        || current.getWidth(0) != width
        || current.getHeight(0) != height
        || drawnVersion != cameraVersion
        || drawnContentVersion != contentVersion;
  }

  /**
   * Makes the color and depth textures {@code width} by {@code height}, reusing them if they are.
   */
  void prepareTargets(int width, int height) {
    GpuTexture current = color;
    if (current != null && current.getWidth(0) == width && current.getHeight(0) == height) {
      return;
    }
    releaseTargets();
    GpuDevice device = RenderSystem.getDevice();
    GpuTexture newColor =
        device.createTexture(LABEL, COLOR_USAGE, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
    GpuTextureView newColorView = device.createTextureView(newColor);
    GpuTexture newDepth =
        device.createTexture(LABEL, DEPTH_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
    color = newColor;
    colorView = newColorView;
    depth = newDepth;
    depthView = device.createTextureView(newDepth);
    textureSetup =
        TextureSetup.singleTexture(
            newColorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
  }

  void markDrawn(int cameraVersion) {
    drawnVersion = cameraVersion;
    drawnContentVersion = contentVersion;
  }

  /** The drawn image; also the source of a thumbnail's copy to memory, hence {@code COPY_SRC}. */
  @Nullable GpuTexture color() {
    return color;
  }

  @Nullable GpuTextureView colorView() {
    return colorView;
  }

  @Nullable GpuTexture depth() {
    return depth;
  }

  @Nullable GpuTextureView depthView() {
    return depthView;
  }

  @Nullable TextureSetup textureSetup() {
    return textureSetup;
  }

  /** GPU memory held: the meshes (whole and cut) and the two textures. */
  long gpuBytes() {
    GpuTexture current = color;
    long pixels = current == null ? 0 : (long) current.getWidth(0) * current.getHeight(0);
    Geometry built = cut;
    long meshBytes = full.bytes() + (built == null ? 0 : built.bytes());
    return meshBytes + pixels * (COLOR_BYTES_PER_PIXEL + DEPTH_BYTES_PER_PIXEL);
  }

  private void releaseTargets() {
    if (colorView != null) {
      colorView.close();
    }
    if (color != null) {
      color.close();
    }
    if (depthView != null) {
      depthView.close();
    }
    if (depth != null) {
      depth.close();
    }
    colorView = null;
    color = null;
    depthView = null;
    depth = null;
    textureSetup = null;
  }

  private static void close(Layer layer) {
    layer.vertices().close();
    GpuBuffer indices = layer.indices();
    if (indices != null) {
      indices.close();
    }
    GpuBuffer layerIndices = layer.layerIndices();
    if (layerIndices != null) {
      layerIndices.close();
    }
  }

  @Override
  public void close() {
    full.close();
    releaseCut();
    releaseTargets();
  }
}
