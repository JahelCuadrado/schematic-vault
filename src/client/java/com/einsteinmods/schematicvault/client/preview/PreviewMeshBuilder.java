package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.volume.LayerOffsets;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.VisibleBlocks;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.datafixers.DataFixer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.serialization.Dynamic;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.TagValueInput;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Builds a design's static mesh on the preview worker, the way vanilla's {@code
 * SectionCompiler#compile} builds a chunk section: block models through {@code
 * ModelBlockRenderer#tesselateBlock} (face culling, ambient occlusion, tints), fluids through
 * {@code FluidRenderer#tesselate}, one buffer per render layer, translucent quads sorted with
 * {@code MeshData#sortQuads}. Unlike a section it covers the whole design in one mesh, skips the
 * blocks {@link VisibleBlocks} hides, and creates the block entities that have a renderer (their
 * NBT loaded with {@code BlockEntity#loadWithComponents}, after the game's data fixer when older).
 *
 * <p>Layers: blocks are meshed in cell order (y slowest), so each render layer's quads come in
 * block Y order, bottom first, and the build notes where each Y layer starts ({@link
 * LayerOffsets}): the preview shows a range of layers by drawing one run of them. A cut build keeps
 * only that range, the cells outside treated as air, so its top and bottom faces are there.
 *
 * <p>Cost: off the render thread only. Memory: 112 bytes per quad until upload, plus (whole design
 * only) one more index buffer for the translucent quads (6 indices each) and 4 bytes per Y layer
 * per render layer.
 */
final class PreviewMeshBuilder {
  /**
   * Most quads one preview may hold (about 220 MB of mesh, freed after upload, and the same on the
   * GPU); a design past it is too large to preview.
   */
  static final long MAX_QUADS = 2_000_000;

  /**
   * Most block entities drawn: each is submitted again every frame the view moves (vanilla's
   * per-frame cost for a chest or a sign); a design past it shows the first ones.
   */
  static final int MAX_BLOCK_ENTITIES = 2_048;

  private static final int QUAD_BYTES = 4 * 28;
  private static final int INDICES_PER_QUAD = 6;
  private static final int MIN_BUFFER_BYTES = 64 * 1024;
  private static final int MAX_INITIAL_BUFFER_BYTES = 256 * 1024 * 1024;

  /** Solid quads expected per visible block when sizing the first buffer. */
  private static final int SOLID_QUADS_PER_BLOCK = 2;

  /** How often (in blocks) the build checks whether it is still wanted and within its quads. */
  private static final int CANCEL_CHECK_MASK = 0xFFF;

  private static final String ID = "id";

  private PreviewMeshBuilder() {}

  /** What the build reads from the client, captured on the render thread. */
  record Settings(
      boolean ambientOcclusion,
      boolean cutoutLeaves,
      BlockStateModelSet blockModels,
      FluidStateModelSet fluidModels,
      BlockColors blockColors,
      BlockEntityRenderDispatcher blockEntityRenderers,
      @Nullable Biome biome,
      HolderLookup.@Nullable Provider registries) {
    /** Render thread. */
    static Settings capture(Minecraft minecraft) {
      Biome plains = null;
      HolderLookup.Provider registries = null;
      if (minecraft.level != null) {
        registries = minecraft.level.registryAccess();
        plains =
            minecraft
                .level
                .registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getValue(Biomes.PLAINS);
      }
      return new Settings(
          minecraft.options.ambientOcclusion().get(),
          minecraft.options.cutoutLeaves().get(),
          minecraft.getModelManager().getBlockStateModelSet(),
          minecraft.getModelManager().getFluidStateModelSet(),
          minecraft.getBlockColors(),
          minecraft.getBlockEntityRenderDispatcher(),
          plains,
          registries);
    }
  }

  /**
   * The mesh of {@code volume} drawn with {@code palette} (one palette per band of layers: the
   * block editor's rules). Worker thread.
   *
   * @param keptLayers Y layers kept; {@link LayerRange#ALL} for the whole design
   * @param cancelled checked now and then; true stops the build with a {@link
   *     CancellationException}
   * @throws VolumeFormatException (too large) past {@link #MAX_QUADS}
   */
  static PreviewMesh build(
      VoxelVolume volume,
      LoadedFile.EditedPalette palette,
      Settings settings,
      LayerRange keptLayers,
      BooleanSupplier cancelled)
      throws VolumeFormatException {
    int[] bandOfY = palette.bandOfY();
    int[] visible = VisibleBlocks.of(volume, palette.opaqueByBand(), bandOfY, keptLayers);
    BlockState[][] states = palette.statesByBand();
    int[] cells = volume.cells();
    int sizeX = volume.sizeX();
    int sizeZ = volume.sizeZ();
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    Map<Integer, BlockEntity> blockEntities = new HashMap<>();
    List<BlockEntity> drawn = new ArrayList<>();
    BlockEntityLoader loader = new BlockEntityLoader(volume, palette, settings);
    for (int index : visible) {
      int entityY = index / sizeX / sizeZ;
      BlockState state = states[bandOfY[entityY]][cells[index]];
      if (!state.hasBlockEntity() || drawn.size() >= MAX_BLOCK_ENTITIES) {
        continue;
      }
      pos.set(index % sizeX, entityY, index / sizeX % sizeZ);
      BlockEntity blockEntity = loader.create(pos.immutable(), state, index);
      if (blockEntity != null) {
        blockEntities.put(index, blockEntity);
        drawn.add(blockEntity);
      }
    }

    PreviewWorld world =
        new PreviewWorld(volume, states, bandOfY, blockEntities, settings.biome(), keptLayers);
    Layers layers = new Layers(visible.length, volume.sizeY());
    int currentLayer = -1;
    ModelBlockRenderer blocks =
        new ModelBlockRenderer(settings.ambientOcclusion(), true, settings.blockColors());
    FluidRenderer fluids = new FluidRenderer(settings.fluidModels());
    float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, 0};
    BlockModelLighter.enableCaching();
    try {
      for (int position = 0; position < visible.length; position++) {
        if ((position & CANCEL_CHECK_MASK) == 0) {
          if (cancelled.getAsBoolean()) {
            throw new CancellationException();
          }
          if (layers.quads() > MAX_QUADS) {
            layers.close();
            throw VolumeFormatException.tooLarge("over " + MAX_QUADS + " quads");
          }
        }
        int index = visible[position];
        int y = index / sizeX / sizeZ;
        BlockState state = states[bandOfY[y]][cells[index]];
        if (state.isAir()) {
          continue;
        }
        int x = index % sizeX;
        int z = index / sizeX % sizeZ;
        if (y != currentLayer) {
          layers.enterLayer(y);
          currentLayer = y;
        }
        pos.set(x, y, z);
        grow(box, x, y, z);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
          // Fluids are written section-relative (x & 15): the output moves them back.
          layers.fluidOrigin(
              SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(x)),
              SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y)),
              SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(z)));
          fluids.tesselate(world, pos, layers, state, fluid);
        }
        if (state.getRenderShape() == RenderShape.MODEL) {
          layers.forceSolid = ModelBlockRenderer.forceOpaque(settings.cutoutLeaves(), state);
          blocks.tesselateBlock(
              layers,
              x,
              y,
              z,
              world,
              pos,
              state,
              settings.blockModels().get(state),
              state.getSeed(pos));
        }
      }
    } catch (RuntimeException exception) {
      layers.close();
      throw exception;
    } finally {
      BlockModelLighter.clearCache();
    }
    for (BlockEntity blockEntity : drawn) {
      BlockPos at = blockEntity.getBlockPos();
      grow(box, at.getX(), at.getY(), at.getZ());
    }
    if (box[0] > box[3]) {
      box = new float[] {0, 0, 0, 0, 0, 0};
    }
    return layers.finish(
        keptLayers.covers(volume.sizeY()),
        drawn,
        box,
        palette.standIns(),
        (box[0] + box[3] + 1) / 2,
        (box[1] + box[4] + 1) / 2,
        (box[2] + box[5] + 1) / 2);
  }

  private static void grow(float[] box, int x, int y, int z) {
    box[0] = Math.min(box[0], x);
    box[1] = Math.min(box[1], y);
    box[2] = Math.min(box[2], z);
    box[3] = Math.max(box[3], x);
    box[4] = Math.max(box[4], y);
    box[5] = Math.max(box[5], z);
  }

  /**
   * Creates block entities that have a renderer, with their saved NBT when there is one (and their
   * type did not change).
   */
  private static final class BlockEntityLoader {
    private final VoxelVolume volume;
    private final LoadedFile.EditedPalette palette;
    private final Settings settings;
    private final @Nullable DataFixer fixer;
    private final int currentVersion;

    BlockEntityLoader(VoxelVolume volume, LoadedFile.EditedPalette palette, Settings settings) {
      this.volume = volume;
      this.palette = palette;
      this.settings = settings;
      this.currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
      int from = volume.blockEntityDataVersion();
      this.fixer = from > 0 && from < currentVersion ? DataFixers.getDataFixer() : null;
    }

    @Nullable BlockEntity create(BlockPos pos, BlockState state, int index) {
      if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
        return null;
      }
      BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
      if (blockEntity == null || settings.blockEntityRenderers().getRenderer(blockEntity) == null) {
        return null;
      }
      // A block whose type a replacement changed starts empty: its old data is dropped.
      NbtTag.CompoundTag saved =
          palette
                  .palettes()[palette.bandOfY()[pos.getY()]]
                  .keepsBlockEntity()[volume.cells()[index]]
              ? volume.blockEntities().get(index)
              : null;
      HolderLookup.Provider registries = settings.registries();
      if (saved != null && registries != null) {
        load(blockEntity, VanillaNbt.compound(saved), registries);
      }
      return blockEntity;
    }

    /** Loads saved NBT; a block entity whose NBT the game cannot read keeps its defaults. */
    private void load(BlockEntity blockEntity, CompoundTag tag, HolderLookup.Provider registries) {
      CompoundTag current = tag;
      if (fixer != null) {
        if (!current.contains(ID)) {
          Identifier type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
          if (type != null) {
            current.putString(ID, type.toString());
          }
        }
        try {
          Tag fixed =
              fixer
                  .update(
                      References.BLOCK_ENTITY,
                      new Dynamic<>(NbtOps.INSTANCE, current),
                      volume.blockEntityDataVersion(),
                      currentVersion)
                  .getValue();
          if (fixed instanceof CompoundTag fixedCompound) {
            current = fixedCompound;
          }
        } catch (RuntimeException exception) {
          // NBT an old version never wrote: load it as saved.
          current = tag;
        }
      }
      try {
        blockEntity.loadWithComponents(
            TagValueInput.create(ProblemReporter.DISCARDING, registries, current));
      } catch (RuntimeException exception) {
        // Damaged NBT: the block entity is drawn with its defaults (a blank sign, a plain banner).
        return;
      }
    }
  }

  /**
   * Where quads go: one buffer per render layer, started on its first quad. Also the fluid
   * renderer's output, moving its section-relative vertices back to the design's coordinates.
   */
  private static final class Layers implements BlockQuadOutput, FluidRenderer.Output {
    private static final ChunkSectionLayer[] RENDER_LAYERS = ChunkSectionLayer.values();

    private final int visibleBlocks;
    private final int height;
    private final long[] blockQuadsByLayer = new long[RENDER_LAYERS.length];
    private final LayerOffsets.Builder[] offsets = new LayerOffsets.Builder[RENDER_LAYERS.length];
    private final EnumMap<ChunkSectionLayer, ByteBufferBuilder> buffers =
        new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, BufferBuilder> builders =
        new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, OffsetConsumer> fluidOutputs =
        new EnumMap<>(ChunkSectionLayer.class);
    private final List<ByteBufferBuilder> extraBuffers = new ArrayList<>();
    private long blockQuads;
    private float originX;
    private float originY;
    private float originZ;

    /** Leaves drawn opaque (the "cutout leaves" option off): all their quads go to solid. */
    boolean forceSolid;

    Layers(int visibleBlocks, int height) {
      this.visibleBlocks = visibleBlocks;
      this.height = height;
      for (ChunkSectionLayer layer : RENDER_LAYERS) {
        offsets[layer.ordinal()] = LayerOffsets.builder(height);
      }
    }

    /** The next blocks are on Y layer {@code y}: every render layer notes where it starts. */
    void enterLayer(int y) {
      for (ChunkSectionLayer layer : RENDER_LAYERS) {
        offsets[layer.ordinal()].enter(y, quads(layer));
      }
    }

    /** Quads written so far to {@code layer}. */
    private int quads(ChunkSectionLayer layer) {
      OffsetConsumer fluid = fluidOutputs.get(layer);
      long fluidVertices = fluid == null ? 0 : fluid.vertices;
      return Math.toIntExact(blockQuadsByLayer[layer.ordinal()] + fluidVertices / 4);
    }

    void fluidOrigin(int x, int y, int z) {
      originX = x;
      originY = y;
      originZ = z;
    }

    long quads() {
      long fluidVertices = 0;
      for (OffsetConsumer output : fluidOutputs.values()) {
        fluidVertices += output.vertices;
      }
      return blockQuads + fluidVertices / 4;
    }

    @Override
    public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
      ChunkSectionLayer layer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
      builder(layer).putBlockBakedQuad(x, y, z, quad, instance);
      blockQuads++;
      blockQuadsByLayer[layer.ordinal()]++;
    }

    @Override
    public VertexConsumer getBuilder(ChunkSectionLayer layer) {
      OffsetConsumer output = fluidOutputs.get(layer);
      if (output == null) {
        output = new OffsetConsumer(builder(layer));
        fluidOutputs.put(layer, output);
      }
      output.set(originX, originY, originZ);
      return output;
    }

    private BufferBuilder builder(ChunkSectionLayer layer) {
      BufferBuilder builder = builders.get(layer);
      if (builder == null) {
        long expected =
            (long) visibleBlocks
                * QUAD_BYTES
                * (layer == ChunkSectionLayer.SOLID ? SOLID_QUADS_PER_BLOCK : 1)
                / (layer == ChunkSectionLayer.SOLID ? 1 : 4);
        ByteBufferBuilder buffer =
            new ByteBufferBuilder(Math.clamp(expected, MIN_BUFFER_BYTES, MAX_INITIAL_BUFFER_BYTES));
        buffers.put(layer, buffer);
        builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, layer.vertexFormat());
        builders.put(layer, builder);
      }
      return builder;
    }

    /**
     * @param wholeDesign whether all Y layers were kept: then the translucent quads also get an
     *     index buffer sorted inside each Y layer, for drawing a range of layers
     */
    PreviewMesh finish(
        boolean wholeDesign,
        List<BlockEntity> blockEntities,
        float[] box,
        SortedMap<String, Block> standIns,
        float centerX,
        float centerY,
        float centerZ) {
      List<PreviewMesh.Layer> layers = new ArrayList<>();
      try {
        for (Map.Entry<ChunkSectionLayer, BufferBuilder> entry : builders.entrySet()) {
          MeshData mesh = entry.getValue().build();
          if (mesh == null) {
            continue;
          }
          LayerOffsets layerOffsets =
              offsets[entry.getKey().ordinal()].finish(quads(entry.getKey()));
          ByteBuffer layerIndices = null;
          if (entry.getKey().translucent()) {
            ByteBufferBuilder indices = new ByteBufferBuilder(MIN_BUFFER_BYTES);
            extraBuffers.add(indices);
            Vector3f towardViewer =
                PreviewCamera.towardViewer(
                    PreviewCamera.DEFAULT_YAW, PreviewCamera.DEFAULT_PITCH, new Vector3f());
            // Farthest first: the distance from the viewer grows as a quad's depth falls.
            VertexSorting.DistanceFunction distance =
                point ->
                    -((point.x() - centerX) * towardViewer.x()
                        + (point.y() - centerY) * towardViewer.y()
                        + (point.z() - centerZ) * towardViewer.z());
            MeshData.SortState sorted = mesh.sortQuads(indices, VertexSorting.byDistance(distance));
            if (wholeDesign && sorted != null) {
              layerIndices = layerIndices(sorted, distance, layerOffsets);
            }
          }
          layers.add(new PreviewMesh.Layer(entry.getKey(), mesh, layerOffsets, layerIndices));
        }
      } catch (RuntimeException exception) {
        layers.forEach(layer -> layer.mesh().close());
        close();
        throw exception;
      }
      List<ByteBufferBuilder> owned = new ArrayList<>(buffers.values());
      owned.addAll(extraBuffers);
      return new PreviewMesh(
          layers,
          owned,
          blockEntities,
          box[0],
          box[1],
          box[2],
          box[3] + 1,
          box[4] + 1,
          box[5] + 1,
          standIns,
          quads(),
          height);
    }

    /**
     * The translucent quads' indices sorted farthest first inside each Y layer, layers bottom
     * first: the run of a range of layers draws exactly those, still sorted inside each one
     * (between layers the lower is drawn first, which a view from above expects).
     */
    private ByteBuffer layerIndices(
        MeshData.SortState sorted, VertexSorting.DistanceFunction distance, LayerOffsets offsets) {
      int bytes = offsets.total() * INDICES_PER_QUAD * sorted.indexType().bytes;
      ByteBufferBuilder target = ByteBufferBuilder.exactlySized(bytes);
      extraBuffers.add(target);
      target.reserve(bytes);
      ByteBufferBuilder.Result result = target.build();
      if (result == null) {
        throw new IllegalStateException("empty translucent layer");
      }
      sorted.writeSortedIndexBuffer(
          result.byteBuffer(),
          points -> {
            Vector3f scratch = new Vector3f();
            float[] keys = new float[points.size()];
            for (int quad = 0; quad < keys.length; quad++) {
              keys[quad] = distance.apply(points.get(quad, scratch));
            }
            return offsets.orderWithinLayers(keys);
          });
      return result.byteBuffer();
    }

    /** Frees every buffer (a build that failed or was cancelled). */
    void close() {
      buffers.values().forEach(ByteBufferBuilder::close);
      extraBuffers.forEach(ByteBufferBuilder::close);
    }
  }

  /** Writes to a buffer with every vertex moved by an offset; counts the vertices. */
  private static final class OffsetConsumer implements VertexConsumer {
    private final VertexConsumer target;
    private float offsetX;
    private float offsetY;
    private float offsetZ;
    private long vertices;

    OffsetConsumer(VertexConsumer target) {
      this.target = target;
    }

    void set(float x, float y, float z) {
      offsetX = x;
      offsetY = y;
      offsetZ = z;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      vertices++;
      target.addVertex(x + offsetX, y + offsetY, z + offsetZ);
      return this;
    }

    @Override
    public void addVertex(
        float x,
        float y,
        float z,
        int color,
        float u,
        float v,
        int overlayCoords,
        int lightCoords,
        float normalX,
        float normalY,
        float normalZ) {
      vertices++;
      target.addVertex(
          x + offsetX,
          y + offsetY,
          z + offsetZ,
          color,
          u,
          v,
          overlayCoords,
          lightCoords,
          normalX,
          normalY,
          normalZ);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      target.setColor(red, green, blue, alpha);
      return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
      target.setColor(color);
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      target.setUv(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      target.setUv1(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      target.setUv2(u, v);
      return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
      target.setUv3(u, v);
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      target.setNormal(x, y, z);
      return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
      target.setLineWidth(width);
      return this;
    }
  }
}
