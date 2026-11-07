package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.client.cache.AtomicFiles;
import com.einsteinmods.schematicvault.client.cache.CatalogCache;
import com.einsteinmods.schematicvault.client.cache.LocalThumbnailStore;
import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.core.catalog.Catalog;
import com.einsteinmods.schematicvault.core.catalog.CatalogThumbnails;
import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.files.Sha256;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.thumbnails.ThumbnailPacing;
import com.einsteinmods.schematicvault.core.thumbnails.ThumbnailPlan;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Makes the thumbnails of a list of local files, one at a time, on the player's request. Each file
 * is read, checked against the block limit and meshed on the preview's low-priority worker, without
 * using any preview's scene: it has its own {@link PreviewScene}, and with it its own textures,
 * freed with the image done, so the browser's turning 3D is never overwritten.
 *
 * <p>A file whose digest is a design's primary file in the catalog on disk, with that design's API
 * thumbnail already downloaded, takes a copy of it: no mesh and no draw. Its cost is one digest of
 * bytes the read had anyway.
 *
 * <p>The draw: the scene goes into the GUI as a picture-in-picture that blits nothing ({@link
 * PreviewRenderer}), from the default {@link PreviewCamera} (north-east, 30° above, fitted), the
 * look of the API's renders. The image then comes back through a GPU copy to a buffer and its fence
 * ({@code Screenshot#takeScreenshot} does the same), so the render thread never waits for the GPU;
 * at most one file is in the pipeline, so nothing piles up. Only then is it encoded and written, on
 * the worker. {@link ThumbnailPacing} spaces the draws.
 *
 * <p>Cost, only while it runs: per frame a field read, a clock read and at most one reused state;
 * per image the mesh's upload and its draw on the render thread (for the median design a few
 * thousand quads), at most 5 % of that thread's time, and 9,216 pixels copied once. The largest
 * meshes (2 M quads) cost one long frame each, as selecting them in the browser does. Render
 * thread, except the worker steps.
 */
public final class ThumbnailGenerator {
  /** The image's side in pixels, the API's thumbnails' too. */
  public static final int SIZE = 96;

  private static final Supplier<String> LABEL = () -> "Schematic Vault thumbnail";

  /** Screen rectangle of the capture's state: drawn nowhere, the GUI only needs one to file it. */
  private static final int STATE_SIDE = 1;

  private static final int OPAQUE = 0xFF000000;

  /** A file of the list. */
  public record Item(Path file, SchematicFormat format, Path thumbnail) {}

  /**
   * What generating a folder would do.
   *
   * @param apiThumbnails the catalog's thumbnail paths by file digest
   */
  public record Scan(List<Item> items, ThumbnailPlan plan, Map<String, String> apiThumbnails) {
    public Scan {
      items = List.copyOf(items);
    }
  }

  /**
   * Finds the files of {@code folder} (not its subfolders) without a thumbnail. Reads the disk on
   * the worker and writes nothing.
   *
   * @param cacheDirectory the mod's caches
   * @param apiBaseUrl the API whose catalog on disk is used
   */
  public static CompletableFuture<Scan> scan(
      Path folder, LocalThumbnailStore store, Path cacheDirectory, String apiBaseUrl) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return scanNow(folder, store, new CatalogCache(cacheDirectory, apiBaseUrl));
          } catch (IOException exception) {
            throw new CompletionException(exception);
          }
        },
        PreviewExecutor.executor());
  }

  private static Scan scanNow(Path folder, LocalThumbnailStore store, CatalogCache catalog)
      throws IOException {
    List<Item> items = new ArrayList<>();
    List<Long> sizes = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      Iterator<Path> paths = files.iterator();
      while (paths.hasNext()) {
        Path file = paths.next();
        Optional<SchematicFormat> format =
            SchematicFormat.fromFileName(file.getFileName().toString());
        if (format.isEmpty() || !Files.isRegularFile(file)) {
          continue;
        }
        LocalThumbnailStore.Located located = store.locate(file);
        if (Files.exists(located.thumbnail())) {
          continue;
        }
        sizes.add(located.size());
        if (!ThumbnailPlan.tooBig(located.size())) {
          items.add(new Item(file, format.get(), located.thumbnail()));
        }
      }
    }
    Map<String, String> apiThumbnails =
        items.isEmpty()
            ? Map.of()
            : catalog
                .read()
                .map(Catalog::schematics)
                .map(CatalogThumbnails::byFileDigest)
                .orElse(Map.of());
    long[] allSizes = sizes.stream().mapToLong(Long::longValue).toArray();
    return new Scan(items, ThumbnailPlan.of(allSizes), apiThumbnails);
  }

  private enum Step {
    /** The worker or the disk has the file: nothing for the render thread. */
    WORKING,
    /** A scene waits for its draw. */
    READY,
    /** Drawn: its pixels are on their way back from the GPU. */
    READING
  }

  private final Minecraft minecraft;
  private final Path apiThumbnailDirectory;
  private final long blockLimit;
  private final Consumer<Path> onWritten;
  private final Runnable onFinished;
  private final Deque<Item> pending;
  private final Map<String, String> apiThumbnails;
  private final int total;
  private final PreviewMeshBuilder.Settings settings;
  private final ThumbnailPacing pacing = new ThumbnailPacing();
  private final PreviewRenderState captureState = new PreviewRenderState(new PreviewCamera(), this);
  private final Executor renderThreadLater;

  /** Loaded on the worker the first time an MCEdit file is read. */
  private volatile @Nullable LegacyBlocks legacy;

  private volatile boolean stopped;

  private Step step = Step.WORKING;
  private @Nullable Item current;
  private @Nullable PreviewScene scene;
  private long uploadNanos;
  private int processed;
  private int made;
  private int skipped;

  /**
   * Starts on the first file.
   *
   * @param onWritten told the path of each thumbnail written
   * @param onFinished told when the last file is done; never after {@link #stop}
   */
  public ThumbnailGenerator(
      Minecraft minecraft,
      Scan scan,
      Path apiThumbnailDirectory,
      long blockLimit,
      Consumer<Path> onWritten,
      Runnable onFinished) {
    this.minecraft = minecraft;
    this.apiThumbnailDirectory = apiThumbnailDirectory;
    this.blockLimit = blockLimit;
    this.onWritten = onWritten;
    this.onFinished = onFinished;
    this.pending = new ArrayDeque<>(scan.items());
    this.apiThumbnails = scan.apiThumbnails();
    this.total = scan.items().size();
    this.skipped = scan.plan().skipped();
    this.settings = PreviewMeshBuilder.Settings.capture(minecraft);
    this.renderThreadLater = task -> minecraft.schedule(minecraft.wrapRunnable(task));
    captureState.place(0, 0, STATE_SIDE, STATE_SIDE, null);
    next();
  }

  /** The files done, with or without a thumbnail. */
  public int processed() {
    return processed;
  }

  public int total() {
    return total;
  }

  /** The thumbnails made so far. */
  public int made() {
    return made;
  }

  /** The files left without one so far, those the size rule left out included. */
  public int skipped() {
    return skipped;
  }

  /** Submits the waiting scene's draw when its turn has come. Once per frame; no allocation. */
  public void extract(GuiGraphicsExtractor graphics) {
    boolean ready = pacing.ready(System.nanoTime());
    if (step == Step.READY && ready) {
      // Submitted again each frame until it is really drawn: no fog buffer means no draw yet.
      graphics.guiRenderState.addPicturesInPictureState(captureState);
    }
  }

  /**
   * Stops after the file in hand, whose thumbnail is dropped; those already written stay. All GPU
   * resources are freed here, or when the step in progress comes back.
   */
  public void stop() {
    stopped = true;
    pending.clear();
    if (step == Step.READY) {
      freeScene();
    }
  }

  private void next() {
    Item item = stopped ? null : pending.poll();
    current = item;
    if (item == null) {
      if (!stopped) {
        onFinished.run();
      }
      return;
    }
    step = Step.WORKING;
    CompletableFuture.supplyAsync(() -> prepare(item), PreviewExecutor.executor())
        .whenCompleteAsync((prepared, failure) -> prepared(prepared), renderThreadLater);
  }

  /** Worker thread: what the file needs next. */
  private Prepared prepare(Item item) {
    try {
      if (stopped) {
        return Prepared.SKIPPED;
      }
      byte[] bytes = PreviewController.readLocalBytes(item.file());
      if (copyApiThumbnail(item, bytes)) {
        return Prepared.MADE;
      }
      LoadedFile file =
          LoadedFile.of(
              SchematicReaders.read(item.format(), bytes, this::legacyTable), bytes, item.format());
      if (file.volume().blockCount() > blockLimit) {
        return Prepared.SKIPPED;
      }
      PreviewMesh mesh =
          PreviewMeshBuilder.build(
              file.volume(),
              file.edited(BlockReplacements.NONE),
              settings,
              LayerRange.ALL,
              () -> stopped);
      if (mesh.isEmpty()) {
        mesh.close();
        return Prepared.SKIPPED;
      }
      return new Prepared(mesh, false);
    } catch (IOException
        | VolumeFormatException
        | NbtFormatException
        | RuntimeException exception) {
      // Damaged, of an unknown version, too large or a renderer failing on an odd block (the same
      // files the browser says it cannot show): this file goes without, the next one goes on.
      return Prepared.SKIPPED;
    }
  }

  /** Copies the API's thumbnail of {@code bytes} when the catalog knows it and it is on disk. */
  private boolean copyApiThumbnail(Item item, byte[] bytes) throws IOException {
    String url = apiThumbnails.get(HexFormat.of().formatHex(Sha256.newDigest().digest(bytes)));
    if (url == null) {
      return false;
    }
    Path cached = ThumbnailCache.fileIn(apiThumbnailDirectory, url);
    if (!Files.isRegularFile(cached)) {
      return false;
    }
    AtomicFiles.write(item.thumbnail(), Files.readAllBytes(cached));
    return true;
  }

  private LegacyBlocks legacyTable() throws IOException {
    LegacyBlocks table = legacy;
    if (table == null) {
      table = LegacyBlocks.load();
      legacy = table;
    }
    return table;
  }

  /** Render thread: uploads a mesh still wanted; {@code prepared} is null if the worker died. */
  private void prepared(@Nullable Prepared prepared) {
    PreviewMesh mesh = prepared == null ? null : prepared.mesh();
    if (mesh != null) {
      if (stopped) {
        mesh.close();
      } else {
        upload(mesh);
      }
      return;
    }
    done(prepared != null && prepared.made());
  }

  private void upload(PreviewMesh mesh) {
    long start = System.nanoTime();
    try {
      PreviewScene uploaded = PreviewScene.upload(minecraft, mesh);
      uploadNanos = System.nanoTime() - start;
      scene = uploaded;
      captureState.scene(uploaded);
      step = Step.READY;
    } catch (RuntimeException exception) {
      // The GPU refused the buffers (out of memory): this file goes without.
      done(false);
    }
  }

  /** Render thread: {@link PreviewRenderer} drew the scene; asks the GPU for the pixels. */
  void drawn(PreviewScene drawnScene, long drawNanos) {
    pacing.drawn(uploadNanos + drawNanos, System.nanoTime());
    step = Step.READING;
    GpuTexture color = drawnScene.color();
    if (color == null) {
      freeScene();
      done(false);
      return;
    }
    int pixelBytes = color.getFormat().blockSize();
    GpuDevice device = RenderSystem.getDevice();
    GpuBuffer pixels =
        device.createBuffer(
            LABEL,
            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
            (long) SIZE * SIZE * pixelBytes);
    device
        .createCommandEncoder()
        .copyTextureToBuffer(color, pixels, 0L, () -> read(pixels, pixelBytes), 0);
  }

  /** Render thread, once the GPU finished the copy: frees the scene, writes on the worker. */
  private void read(GpuBuffer pixels, int pixelBytes) {
    NativeImage image = null;
    try (pixels;
        GpuBufferSlice.MappedView view = pixels.map(true, false)) {
      if (!stopped) {
        image = new NativeImage(SIZE, SIZE, false);
        for (int y = 0; y < SIZE; y++) {
          for (int x = 0; x < SIZE; x++) {
            int abgr = view.data().getInt((x + y * SIZE) * pixelBytes);
            // The texture holds its bottom row first.
            image.setPixelABGR(x, SIZE - y - 1, abgr | OPAQUE);
          }
        }
      }
    }
    freeScene();
    Item item = current;
    if (image == null || item == null) {
      if (image != null) {
        image.close();
      }
      done(false);
      return;
    }
    NativeImage finished = image;
    step = Step.WORKING;
    CompletableFuture.supplyAsync(
            () -> write(finished, item.thumbnail()), PreviewExecutor.executor())
        .whenCompleteAsync(
            (written, failure) -> done(Boolean.TRUE.equals(written)), renderThreadLater);
  }

  /** Worker thread: the PNG, through a temporary file; false when the disk refused it. */
  private static boolean write(NativeImage image, Path thumbnail) {
    try (image) {
      Path temp = AtomicFiles.createTemp(thumbnail);
      try {
        image.writeToFile(temp);
        AtomicFiles.moveInto(temp, thumbnail);
        return true;
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException exception) {
      return false;
    }
  }

  /** A file is finished; the next one starts. */
  private void done(boolean success) {
    Item item = current;
    if (success && item != null) {
      made++;
      onWritten.accept(item.thumbnail());
    } else {
      skipped++;
    }
    processed++;
    next();
  }

  private void freeScene() {
    PreviewScene shown = scene;
    if (shown != null) {
      shown.close();
      scene = null;
    }
    captureState.scene(null);
  }

  /**
   * What the worker found.
   *
   * @param mesh the mesh to draw; null when nothing is left to draw
   * @param made whether a thumbnail was made without a draw
   */
  private record Prepared(@Nullable PreviewMesh mesh, boolean made) {
    private static final Prepared MADE = new Prepared(null, true);
    private static final Prepared SKIPPED = new Prepared(null, false);
  }
}
