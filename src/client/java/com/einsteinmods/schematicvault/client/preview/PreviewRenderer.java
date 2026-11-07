package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.preview.TurnTable;
import com.einsteinmods.schematicvault.core.volume.LayerOffsets;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link PreviewScene} into its own texture and blits it into the GUI. Registered once with
 * Fabric's picture-in-picture registry: vanilla calls it during the GUI render ({@code
 * GuiRenderer#preparePictureInPicture}), with the UI lightmap bound, only for frames where a
 * preview added its {@link PreviewRenderState}. It never uses the base class's own texture.
 *
 * <p>A redraw happens only when the scene is new, resized or the camera moved: an orthographic
 * projection over the texture, one render pass drawing the uploaded layers with vanilla's block
 * pipelines ({@code RenderPipelines.SOLID_BLOCK}, {@code CUTOUT_BLOCK}, {@code TRANSLUCENT_BLOCK},
 * those of moving blocks: block vertex format, model-view transform, block atlas and lightmap), the
 * way {@code DebugCrosshairRenderer#render} draws its persistent buffer; then the block entities,
 * submitted with their vanilla renderers and drawn by the feature dispatcher, with the view in the
 * model-view matrix and level lighting, as in the world. Every other frame only adds one blit.
 *
 * <p>Layers: it draws the run of each render layer that holds the shown Y layers ({@link
 * PreviewScene#geometry}, offsets per layer), translucent quads with their per-layer sorted
 * indices, and only the block entities of those layers: at most the same draw calls, fewer quads.
 *
 * <p>A state with a capture ({@link ThumbnailGenerator}) draws the same way into a 96 px texture of
 * its own scene and blits nothing.
 *
 * <p>Cost: nothing while no preview is shown. A still frame: one blit. A moving frame: the draw
 * calls of the mesh (one per {@link #BATCH_QUADS} quads) plus each block entity's submit.
 */
public final class PreviewRenderer extends PictureInPictureRenderer<PreviewRenderState> {
  private static final String LABEL = "schematic vault preview";
  private static final Supplier<String> PASS_LABEL = () -> "Schematic Vault preview";

  /**
   * Quads per draw call with the shared quad indices: 65,536 vertices, so every index fits a short,
   * as in a chunk section.
   */
  private static final int BATCH_QUADS = 16_384;

  private static final int INDICES_PER_QUAD = 6;
  private static final int VERTICES_PER_QUAD = 4;

  /** Share of the area the first framing fills: a margin around the design. */
  private static final float FILL = 0.9F;

  /** Depth margin beyond the design's bounding sphere, in texture pixels. */
  private static final float DEPTH_MARGIN = 16.0F;

  /** The thumbnails' dark backdrop (0xFF1E1E24), opaque: what the design is drawn over. */
  private static final Vector4fc BACKGROUND =
      new Vector4f(0x1E / 255.0F, 0x1E / 255.0F, 0x24 / 255.0F, 1.0F);

  private static final int CORNERS = 8;
  private static final int WHITE = -1;
  private static final String DYNAMIC_TRANSFORMS = "DynamicTransforms";
  private static final String BLOCK_ATLAS = "Sampler0";
  private static final String LIGHTMAP = "Sampler2";

  private final PoseStack poseStack = new PoseStack();
  private final SubmitNodeStorage storage = new SubmitNodeStorage();
  private final CameraRenderState cameraState = new CameraRenderState();
  private final Projection projection = new Projection();
  private final Matrix4f modelView = new Matrix4f();
  private final Matrix4f fitTurn = new Matrix4f();
  private final Quaternionf rotation = new Quaternionf();
  private final Quaternionf fitRotation = new Quaternionf();
  private final Vector3f corner = new Vector3f();

  /** Created on the first redraw: nothing is allocated until a preview is shown. */
  private @Nullable ProjectionMatrixBuffer projectionBuffer;

  @Override
  public void prepare(
      PreviewRenderState state,
      GuiRenderState guiRenderState,
      FeatureRenderDispatcher featureRenderDispatcher,
      int guiScale) {
    PreviewScene scene = state.scene();
    ThumbnailGenerator capture = state.capture();
    if (capture != null) {
      capture(scene, state.camera(), capture, featureRenderDispatcher);
      return;
    }
    int width = (state.x1() - state.x0()) * guiScale;
    int height = (state.y1() - state.y0()) * guiScale;
    // No fog buffer outside a world (vanilla sets it after the level render): nothing to draw.
    if (scene == null || width <= 0 || height <= 0 || RenderSystem.getShaderFog() == null) {
      return;
    }
    PreviewCamera camera = state.camera();
    if (scene.needsDraw(width, height, camera.version())) {
      scene.prepareTargets(width, height);
      draw(scene, camera, featureRenderDispatcher, width, height, guiScale);
      scene.markDrawn(camera.version());
    }
    TextureSetup textures = scene.textureSetup();
    if (textures == null) {
      return;
    }
    // The texture is stored bottom row first: v runs from 1 at the top to 0.
    guiRenderState.addBlitToCurrentLayer(
        new BlitRenderState(
            RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
            textures,
            state.pose(),
            state.x0(),
            state.y0(),
            state.x1(),
            state.y1(),
            0.0F,
            1.0F,
            1.0F,
            0.0F,
            WHITE,
            state.scissorArea(),
            state.bounds()));
  }

  /**
   * Draws {@code scene} into its own {@link ThumbnailGenerator#SIZE} texture, which nothing blits,
   * and hands it to {@code capture}. Here and not outside the GUI render because the world's fog
   * buffer and the feature dispatcher the block entities need are in place, as for any preview. It
   * has its own textures, so a preview on screen in the same frame loses nothing.
   */
  private void capture(
      @Nullable PreviewScene scene,
      PreviewCamera camera,
      ThumbnailGenerator capture,
      FeatureRenderDispatcher features) {
    if (scene == null || RenderSystem.getShaderFog() == null) {
      return;
    }
    long start = System.nanoTime();
    scene.prepareTargets(ThumbnailGenerator.SIZE, ThumbnailGenerator.SIZE);
    draw(scene, camera, features, ThumbnailGenerator.SIZE, ThumbnailGenerator.SIZE, 1);
    capture.drawn(scene, System.nanoTime() - start);
  }

  private void draw(
      PreviewScene scene,
      PreviewCamera camera,
      FeatureRenderDispatcher features,
      int width,
      int height,
      int guiScale) {
    GpuTexture color = scene.color();
    GpuTexture depth = scene.depth();
    GpuTextureView colorView = scene.colorView();
    GpuTextureView depthView = scene.depthView();
    if (color == null || depth == null || colorView == null || depthView == null) {
      return;
    }
    RenderSystem.getDevice()
        .createCommandEncoder()
        .clearColorAndDepthTextures(color, BACKGROUND, depth, 0.0);

    float scale =
        (camera.fitsWholeTurn()
                ? wholeTurnScale(scene, camera.pitch(), width, height)
                : fittedScale(scene, width, height))
            * camera.zoom();
    float radius =
        0.5F
            * (float)
                Math.sqrt(
                    scene.sizeX() * scene.sizeX()
                        + scene.sizeY() * scene.sizeY()
                        + scene.sizeZ() * scene.sizeZ());
    float halfDepth = radius * scale + DEPTH_MARGIN;
    ProjectionMatrixBuffer buffer = projectionBuffer;
    if (buffer == null) {
      buffer = new ProjectionMatrixBuffer(LABEL);
      projectionBuffer = buffer;
    }
    projection.setupOrtho(-halfDepth, halfDepth, width, height, true);
    RenderSystem.setProjectionMatrix(buffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

    PreviewCamera.rotation(camera.yaw(), camera.pitch(), rotation);
    modelView
        .identity()
        .translate(
            width / 2.0F + camera.panX() * guiScale, height / 2.0F + camera.panY() * guiScale, 0.0F)
        .scale(scale, -scale, scale)
        .rotate(rotation)
        .translate(-scene.centerX(), -scene.centerY(), -scene.centerZ());

    PreviewScene.Geometry geometry = scene.geometry();
    LayerRange shownLayers = scene.shownLayers();
    drawLayers(geometry, shownLayers, colorView, depthView);
    LayerOffsets blockEntities = geometry.blockEntityOffsets();
    int firstBlockEntity = blockEntities.start(shownLayers.bottom());
    int endBlockEntity = blockEntities.end(shownLayers.top() + 1);
    if (firstBlockEntity < endBlockEntity) {
      drawBlockEntities(geometry, firstBlockEntity, endBlockEntity, features, colorView, depthView);
    }
  }

  /**
   * Pixels per block that fit the design's box into the area at the first view's angle, so the zoom
   * and the reset are relative to that framing.
   */
  private float fittedScale(PreviewScene scene, int width, int height) {
    fitTurn.rotation(
        PreviewCamera.rotation(
            PreviewCamera.DEFAULT_YAW, PreviewCamera.DEFAULT_PITCH, fitRotation));
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    for (int index = 0; index < CORNERS; index++) {
      corner.set(
          (index & 1) == 0 ? 0.0F : scene.sizeX(),
          (index & 2) == 0 ? 0.0F : scene.sizeY(),
          (index & 4) == 0 ? 0.0F : scene.sizeZ());
      fitTurn.transformPosition(corner);
      minX = Math.min(minX, corner.x);
      minY = Math.min(minY, corner.y);
      maxX = Math.max(maxX, corner.x);
      maxY = Math.max(maxY, corner.y);
    }
    float spanX = Math.max(1.0F, maxX - minX);
    float spanY = Math.max(1.0F, maxY - minY);
    return Math.min(width * FILL / spanX, height * FILL / spanY);
  }

  /** Pixels per block that keep the design's box inside the area while it turns. */
  private static float wholeTurnScale(PreviewScene scene, float pitch, int width, int height) {
    float spanX = Math.max(1.0F, TurnTable.widthSpan(scene.sizeX(), scene.sizeZ()));
    float spanY =
        Math.max(1.0F, TurnTable.heightSpan(scene.sizeX(), scene.sizeY(), scene.sizeZ(), pitch));
    return Math.min(width * FILL / spanX, height * FILL / spanY);
  }

  /** Draws each render layer's quads of the Y layers {@code shownLayers}. */
  private void drawLayers(
      PreviewScene.Geometry geometry,
      LayerRange shownLayers,
      GpuTextureView colorView,
      GpuTextureView depthView) {
    Minecraft minecraft = Minecraft.getInstance();
    GpuTextureView atlas =
        minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTextureView();
    GpuSampler atlasSampler =
        RenderSystem.getSamplerCache()
            .getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.NEAREST,
                true);
    GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView);
    RenderSystem.AutoStorageIndexBuffer quadIndices =
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
    try (RenderPass pass =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                PASS_LABEL, colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
      List<PreviewScene.Layer> layers = geometry.layers();
      for (int index = 0; index < layers.size(); index++) {
        PreviewScene.Layer layer = layers.get(index);
        int firstQuad = layer.offsets().start(shownLayers.bottom());
        int endQuad = layer.offsets().end(shownLayers.top() + 1);
        int shownQuads = endQuad - firstQuad;
        if (shownQuads == 0) {
          continue;
        }
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline(layer.layer())));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform(DYNAMIC_TRANSFORMS, transform);
        pass.setUniform(BLOCK_ATLAS, atlas, atlasSampler);
        pass.setUniform(LIGHTMAP, minecraft.gameRenderer.lightmap(), lightmapSampler);
        pass.setVertexBuffer(0, layer.vertexSlice());
        // A part of the quads needs the translucent order kept inside each Y layer; without those
        // indices (never for the whole design) they are drawn in mesh order, unsorted.
        GpuBuffer sorted = shownQuads == layer.quads() ? layer.indices() : layer.layerIndices();
        if (sorted != null) {
          pass.setIndexBuffer(sorted, layer.indexType());
          pass.drawIndexed(shownQuads * INDICES_PER_QUAD, 1, firstQuad * INDICES_PER_QUAD, 0, 0);
          continue;
        }
        GpuBuffer indices =
            quadIndices.getBuffer(Math.min(shownQuads, BATCH_QUADS) * INDICES_PER_QUAD);
        pass.setIndexBuffer(indices, quadIndices.type());
        for (int first = firstQuad; first < endQuad; first += BATCH_QUADS) {
          int quads = Math.min(BATCH_QUADS, endQuad - first);
          pass.drawIndexed(quads * INDICES_PER_QUAD, 1, 0, first * VERTICES_PER_QUAD, 0);
        }
      }
    }
  }

  private static RenderPipeline pipeline(ChunkSectionLayer layer) {
    return switch (layer) {
      case SOLID -> RenderPipelines.SOLID_BLOCK;
      case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
      case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
    };
  }

  /**
   * Submits the block entities from {@code first} to before {@code end} (those of the layers shown)
   * at their positions with the view in the model-view matrix (so their normals stay in world space
   * under level lighting), then draws them over the mesh's depth.
   */
  private void drawBlockEntities(
      PreviewScene.Geometry geometry,
      int first,
      int end,
      FeatureRenderDispatcher features,
      GpuTextureView colorView,
      GpuTextureView depthView) {
    Minecraft minecraft = Minecraft.getInstance();
    BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
    Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
    modelViewStack.pushMatrix();
    modelViewStack.set(modelView);
    minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
    try {
      List<BlockEntityRenderState> states = geometry.blockEntities();
      for (int index = first; index < end; index++) {
        BlockEntityRenderState state = states.get(index);
        BlockPos pos = state.blockPos;
        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
        dispatcher.submit(state, poseStack, storage, cameraState);
        poseStack.popPose();
      }
      try (FeatureRenderDispatcher.PreparedFrame frame = features.prepareFrame(storage);
          RenderPass pass =
              RenderSystem.getDevice()
                  .createCommandEncoder()
                  .createRenderPass(
                      PASS_LABEL, colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
        RenderSystem.bindDefaultUniforms(pass);
        FeatureRenderDispatcher.renderAllFeatures(pass, frame);
      }
    } finally {
      // The GUI render expects item lighting (GameRenderer sets it before the GUI).
      minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
      modelViewStack.popMatrix();
    }
  }

  /** Never called: {@link #prepare} draws into the scene's own texture. */
  @Override
  protected void renderToTexture(
      PreviewRenderState state, PoseStack stack, SubmitNodeCollector collector) {
    throw new UnsupportedOperationException("previews draw into their scene's texture");
  }

  @Override
  public Class<PreviewRenderState> getRenderStateClass() {
    return PreviewRenderState.class;
  }

  @Override
  protected String getTextureLabel() {
    return LABEL;
  }

  @Override
  public void close() {
    super.close();
    if (projectionBuffer != null) {
      projectionBuffer.close();
      projectionBuffer = null;
    }
  }
}
