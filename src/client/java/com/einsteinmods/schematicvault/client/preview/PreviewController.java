package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.client.DesignEdits;
import com.einsteinmods.schematicvault.client.download.Downloader;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.core.catalog.SchematicFile;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The 3D preview of the design selected in the vault: which file it shows, where it stands
 * (loading, ready, over the block limit, failed), its camera and its GPU scene. A file is read from
 * the schematics folder when already saved there with the API's digest, else fetched into memory
 * through the API client and checked as {@link Downloader} checks it (nothing is written); it is
 * parsed and meshed on the preview's own worker ({@link PreviewExecutor}), then uploaded on the
 * render thread. The last few parsed files stay in memory for this session, with the bytes they
 * were read from.
 *
 * <p>Local files: a controller made with {@link #forLocalFiles} shows files of the player's own
 * ({@link #showLocal}), without an API client: read from their path on the same worker, no digest,
 * the format given by the caller. The block limit applies once the file is read, the only moment
 * its blocks are known. Each selection replaces the last: a read still queued for an older one does
 * nothing when its turn comes, so selecting fast never piles up work.
 *
 * <p>Block editor: the shown file's blocks by type ({@link #blockList}) and their replacements,
 * rules kept per file in the session's {@link DesignEdits}. A rule applies to the layers shown when
 * it is chosen. A change remaps one palette per band of layers on the render thread (a few hundred
 * entries each, microseconds; cells are never copied) and rebuilds the mesh on the worker; the old
 * scene stays on screen until the new one is uploaded, and only the newest rebuild is kept. The
 * edited download rewrites the bytes already in memory ({@link #editedFile}).
 *
 * <p>Layers (a {@link LayerRange}, {@link #layerRange}): {@link #showLayers} cuts the scene at once
 * by drawing one run of its Y-ordered mesh (no rebuild); {@link #settleLayers}, when the slider
 * rests, builds the cut mesh on the worker (cells outside the range treated as air, so its top and
 * bottom layers are solid) and shows it in place of the run once uploaded; only the newest cut
 * build is kept. The whole design's mesh stays for the next drag; "all layers" frees the cut one.
 * An edit keeps the layers shown: its rebuild makes the cut mesh too.
 *
 * <p>The material list follows the layers once they rest ({@link #shownBlockList}): counting the
 * cells of a range takes about 3 ms on a 500,000-block design, too much for each layer crossed in a
 * drag.
 *
 * <p>Resources: a new selection, {@link #release} and {@link #close} free the scene's GPU buffers
 * and textures at once; a build that finishes for a selection no longer shown frees its mesh
 * without uploading it. Render thread, except the worker steps.
 */
public final class PreviewController implements AutoCloseable {
  /** Files kept for the last designs looked at (the newest is always kept). */
  private static final int MAX_VOLUMES = 3;

  /** Cells kept in all: 8 M, a 32 MB index array (the median design has 14 K cells). */
  private static final long MAX_CACHED_CELLS = 1 << 23;

  private static final SortedMap<String, Block> NO_STAND_INS =
      Collections.unmodifiableSortedMap(new TreeMap<>());

  /** What the preview shows. */
  public enum Status {
    /** No file to preview yet (the design's record is loading) or none at all. */
    NONE,
    /** More blocks than the limit: thumbnail, a notice and "Show anyway". */
    OVER_LIMIT,
    LOADING,
    READY,
    FAILED_DOWNLOAD,
    /** The file is damaged, of an unknown version, or has nothing the game can draw. */
    FAILED_READ,
    /** Past the box or mesh limits. */
    TOO_LARGE
  }

  /** A local file's blocks are unknown until it is read. */
  private static final long UNKNOWN_BLOCKS = -1;

  private static final char KEY_SEPARATOR = '|';

  /** The design id of every local file in {@link DesignEdits}: the file id is its path. */
  private static final String LOCAL_DESIGN = "";

  private final Minecraft minecraft;
  private final @Nullable ApiClient api;
  private final long blockLimit;
  private final DesignEdits edits;
  private final Map<String, LoadedFile> files = new LinkedHashMap<>(4, 0.75F, true);
  private final Map<String, String> chosenFiles = new HashMap<>();
  private final PreviewCamera camera = new PreviewCamera();
  private final PreviewRenderState renderState = new PreviewRenderState(camera);
  private final AtomicInteger generation = new AtomicInteger();

  /** Bumped by each cut build: an older one stops or is freed when it finishes. */
  private final AtomicInteger cutGeneration = new AtomicInteger();

  /**
   * Runs a load's last step on the render thread, always queued: never inside the call that started
   * the load, even when it failed at once (the screen is rebuilding then).
   */
  private final Executor renderThreadLater;

  /** Loaded on the worker the first time an MCEdit file is read; worker thread only. */
  private volatile @Nullable LegacyBlocks legacy;

  private Runnable listener = () -> {};
  private @Nullable SchematicSummary schematic;
  private @Nullable SchematicFile file;
  private @Nullable Path savedFile;

  /** The local file shown ({@link #showLocal}) and its format; null for a catalog design. */
  private @Nullable Path localFile;

  private @Nullable SchematicFormat localFormat;

  /** The file {@link #start} last loaded. */
  private @Nullable Source source;

  /** A local file's blocks, known once it is read over the limit. */
  private long localBlocks;

  private boolean allowOverLimit;
  private Status status = Status.NONE;
  private SortedMap<String, Block> standIns = NO_STAND_INS;
  private @Nullable PreviewScene scene;

  /** The shown file once its first build finished, and its palette with the replacements. */
  private @Nullable LoadedFile loaded;

  private LoadedFile.@Nullable EditedPalette edited;

  /** The editor's list for {@link #edited}, made when first asked for. */
  private @Nullable BlockList blockList;

  /** The list for {@link #shownLayers} alone, made when first asked for; never for all layers. */
  private @Nullable BlockList rangeBlockList;

  /** A rebuild after a change is running: the scene on screen is the previous one. */
  private boolean rebuilding;

  /** The Y layers shown, kept across edits; {@link LayerRange#ALL} for all. */
  private LayerRange shownLayers = LayerRange.ALL;

  /** The layers a cut build still running was started for; null when none runs. */
  private @Nullable LayerRange pendingCutLayers;

  public PreviewController(Minecraft minecraft, ApiClient api, int blockLimit, DesignEdits edits) {
    this(minecraft, blockLimit, edits, api);
  }

  private PreviewController(
      Minecraft minecraft, int blockLimit, DesignEdits edits, @Nullable ApiClient api) {
    this.minecraft = minecraft;
    this.api = api;
    this.blockLimit = blockLimit;
    this.edits = edits;
    this.renderThreadLater = task -> minecraft.schedule(minecraft.wrapRunnable(task));
  }

  /** A preview of local files only: it holds no API client and never touches the network. */
  public static PreviewController forLocalFiles(
      Minecraft minecraft, int blockLimit, DesignEdits edits) {
    return new PreviewController(minecraft, blockLimit, edits, null);
  }

  /**
   * Called on the render thread when a load finishes (the status changed by itself); changes the
   * screen asks for ({@link #show}, {@link #choose}, {@link #showAnyway}) never call it.
   */
  public void listen(Runnable onChange) {
    listener = onChange;
  }

  public Status status() {
    return status;
  }

  /**
   * Blocks the game does not know in the shown file (a mod's the player lacks), by id, with the
   * vanilla block drawn for each; replaced ones left out.
   */
  public SortedMap<String, Block> standIns() {
    return standIns;
  }

  public long blockLimit() {
    return blockLimit;
  }

  /** The blocks of the local file shown, known when it is {@link Status#OVER_LIMIT}. */
  public long localBlocks() {
    return localBlocks;
  }

  /** The local file shown; null for none or a catalog design. */
  public @Nullable Path localFile() {
    return localFile;
  }

  public @Nullable SchematicFile file() {
    return file;
  }

  /** The shown file's format; null while nothing is shown. */
  public @Nullable SchematicFormat format() {
    Source shown = source;
    return shown == null ? null : shown.format();
  }

  public PreviewCamera camera() {
    return camera;
  }

  /** The state the widget hands to the GUI each frame the scene is ready. */
  public PreviewRenderState renderState() {
    return renderState;
  }

  /**
   * Shows {@code shown}: the file the player picked for it in this session, else its primary file
   * (none until its record is loaded). Nothing happens when that is already what is shown.
   *
   * @param files the design's files, empty until its record is loaded
   * @param saved where files are already saved, by file id
   */
  public void show(
      SchematicSummary shown,
      @Nullable SchematicFile primary,
      List<SchematicFile> files,
      Map<String, Path> saved) {
    SchematicSummary current = schematic;
    if (current == null || !current.id().equals(shown.id())) {
      release();
      schematic = shown;
      camera.reset();
    }
    SchematicFile picked = primary;
    String chosenId = chosenFiles.get(shown.id());
    for (SchematicFile candidate : files) {
      if (candidate.id().equals(chosenId)) {
        picked = candidate;
      }
    }
    SchematicFile shownFile = file;
    if (picked == null || shownFile != null && shownFile.sha256().equals(picked.sha256())) {
      return;
    }
    file = picked;
    savedFile = saved.get(picked.id());
    start();
  }

  /**
   * Shows the file at {@code path}, read as {@code format}, in place of whatever was shown; nothing
   * happens when it is already shown.
   */
  public void showLocal(Path path, SchematicFormat format) {
    if (path.equals(localFile)) {
      return;
    }
    release();
    localFile = path;
    localFormat = format;
    camera.reset();
    start();
  }

  /** Previews another of the shown design's files. */
  public void choose(SchematicFile chosen, @Nullable Path saved) {
    SchematicSummary current = schematic;
    SchematicFile shownFile = file;
    if (current == null || shownFile != null && shownFile.sha256().equals(chosen.sha256())) {
      return;
    }
    chosenFiles.put(current.id(), chosen.id());
    file = chosen;
    savedFile = saved;
    camera.reset();
    start();
  }

  /** Loads the 3D of a design over the block limit. */
  public void showAnyway() {
    if (status == Status.OVER_LIMIT) {
      allowOverLimit = true;
      start();
    }
  }

  private void start() {
    freeScene();
    forgetEditor();
    shownLayers = LayerRange.ALL;
    cutGeneration.incrementAndGet();
    pendingCutLayers = null;
    int build = generation.incrementAndGet();
    Source shown = currentSource();
    source = shown;
    standIns = NO_STAND_INS;
    if (shown == null) {
      status = Status.NONE;
      return;
    }
    if (shown.blocks() > blockLimit && !allowOverLimit) {
      status = Status.OVER_LIMIT;
      return;
    }
    status = Status.LOADING;
    PreviewMeshBuilder.Settings settings = PreviewMeshBuilder.Settings.capture(minecraft);
    BlockReplacements replacements = edits.get(shown.designId(), shown.fileId());
    boolean checkLimit = shown.blocks() == UNKNOWN_BLOCKS && !allowOverLimit;
    SchematicFile target = file;
    LoadedFile cached = files.get(shown.key());
    CompletableFuture<LoadedFile> read;
    if (cached != null) {
      read = CompletableFuture.completedFuture(cached);
    } else {
      read = target != null ? read(target, savedFile) : readLocal(shown, build);
    }
    read.thenApplyAsync(
            readFile -> {
              if (checkLimit && readFile.volume().blockCount() > blockLimit) {
                return new Built(readFile, null, null);
              }
              LoadedFile.EditedPalette palette = readFile.edited(replacements);
              return new Built(readFile, palette, mesh(readFile, palette, settings, build));
            },
            PreviewExecutor.executor())
        .whenCompleteAsync(
            (built, failure) -> finish(build, shown.key(), built, failure), renderThreadLater);
  }

  /** What {@link #start} loads: a catalog file or a local one; null when nothing is chosen. */
  private @Nullable Source currentSource() {
    Path local = localFile;
    SchematicFormat format = localFormat;
    if (local != null && format != null) {
      return new Source(localKey(local), LOCAL_DESIGN, local.toString(), format, UNKNOWN_BLOCKS);
    }
    SchematicSummary current = schematic;
    SchematicFile target = file;
    if (current == null || target == null) {
      return null;
    }
    return new Source(
        target.sha256(), current.id(), target.id(), target.format(), current.blockCount());
  }

  /** A local file's key among the cached files: a changed file reads again. */
  private static String localKey(Path path) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return path.toString()
          + KEY_SEPARATOR
          + attributes.size()
          + KEY_SEPARATOR
          + attributes.lastModifiedTime().toMillis();
    } catch (IOException exception) {
      // Gone or unreadable: the read itself reports it.
      return path.toString();
    }
  }

  private PreviewMesh mesh(
      LoadedFile readFile,
      LoadedFile.EditedPalette palette,
      PreviewMeshBuilder.Settings settings,
      int build) {
    try {
      return PreviewMeshBuilder.build(
          readFile.volume(), palette, settings, LayerRange.ALL, () -> generation.get() != build);
    } catch (VolumeFormatException exception) {
      throw new CompletionException(exception);
    }
  }

  /**
   * The mesh of {@code layers} alone; null when a newer cut build replaced it (the newer one
   * follows). Worker thread.
   */
  private @Nullable PreviewMesh cutMesh(
      LoadedFile readFile,
      LoadedFile.EditedPalette palette,
      PreviewMeshBuilder.Settings settings,
      LayerRange layers,
      int build,
      int cutBuild) {
    try {
      return PreviewMeshBuilder.build(
          readFile.volume(),
          palette,
          settings,
          layers,
          () -> generation.get() != build || cutGeneration.get() != cutBuild);
    } catch (CancellationException exception) {
      // The slider moved on (or the file changed): the newer request builds its own.
      return null;
    } catch (VolumeFormatException exception) {
      // Fewer quads than the whole design, which fit: the range stays drawn as a run of it.
      return null;
    }
  }

  /**
   * The file's bytes and volume: from disk when saved with the right digest, else fetched; parsed
   * on the worker.
   */
  private CompletableFuture<LoadedFile> read(SchematicFile target, @Nullable Path saved) {
    CompletableFuture<byte[]> bytes =
        saved == null
            ? fetch(target)
            : CompletableFuture.supplyAsync(() -> readSaved(target, saved), ApiClient.executor())
                .thenCompose(
                    local ->
                        local != null ? CompletableFuture.completedFuture(local) : fetch(target));
    return bytes.thenApplyAsync(data -> parse(data, target.format()), PreviewExecutor.executor());
  }

  /**
   * A local file's bytes and volume, on the worker; stops when its turn comes after a newer
   * selection, so the files passed over are never read.
   */
  private CompletableFuture<LoadedFile> readLocal(Source shown, int build) {
    Path path = localFile;
    if (path == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("no local file"));
    }
    return CompletableFuture.supplyAsync(
        () -> {
          if (generation.get() != build) {
            throw new CancellationException();
          }
          return parse(readLocalBytes(path), shown.format());
        },
        PreviewExecutor.executor());
  }

  static byte[] readLocalBytes(Path path) {
    try {
      if (Files.size(path) > Downloader.MAX_DOWNLOAD_BYTES) {
        throw new CompletionException(VolumeFormatException.tooLarge("file too big: " + path));
      }
      return Files.readAllBytes(path);
    } catch (IOException exception) {
      throw new CompletionException(
          VolumeFormatException.unreadable("cannot read " + path, exception));
    }
  }

  private LoadedFile parse(byte[] data, SchematicFormat format) {
    try {
      return LoadedFile.of(SchematicReaders.read(format, data, this::legacyTable), data, format);
    } catch (VolumeFormatException | NbtFormatException exception) {
      throw new CompletionException(exception);
    }
  }

  private CompletableFuture<byte[]> fetch(SchematicFile target) {
    ApiClient client = api;
    if (client == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("local files only"));
    }
    return client
        .fetch(target.url(), Downloader.maxBytes(target))
        .thenApplyAsync(
            data -> {
              Downloader.verify(target, data);
              return data;
            },
            ApiClient.executor());
  }

  /** The saved file's bytes, or null when it changed since (its digest no longer matches). */
  private static byte @Nullable [] readSaved(SchematicFile target, Path saved) {
    try {
      if (Files.size(saved) > Downloader.maxBytes(target)) {
        return null;
      }
      byte[] data = Files.readAllBytes(saved);
      Downloader.verify(target, data);
      return data;
    } catch (IOException | ApiException exception) {
      // Edited, moved or unreadable since it was saved: fetch the API's copy instead.
      return null;
    }
  }

  private LegacyBlocks legacyTable() throws IOException {
    LegacyBlocks table = legacy;
    if (table == null) {
      table = LegacyBlocks.load();
      legacy = table;
    }
    return table;
  }

  /** Render thread: uploads a build still wanted, frees one that is not. */
  private void finish(int build, String key, @Nullable Built built, @Nullable Throwable failure) {
    if (built != null) {
      remember(key, built.file());
    }
    PreviewMesh mesh = built == null ? null : built.mesh();
    if (build != generation.get()) {
      if (mesh != null) {
        mesh.close();
      }
      return;
    }
    if (built == null) {
      setStatus(failureStatus(failure));
      return;
    }
    LoadedFile.EditedPalette palette = built.palette();
    if (mesh == null || palette == null) {
      // Read to learn its size: kept in the cache, "Show anyway" does not read it again.
      localBlocks = built.file().volume().blockCount();
      setStatus(Status.OVER_LIMIT);
      return;
    }
    standIns = mesh.standIns();
    if (mesh.isEmpty() && palette.replacements().isEmpty()) {
      mesh.close();
      setStatus(Status.FAILED_READ);
      return;
    }
    loaded = built.file();
    edited = palette;
    upload(mesh);
  }

  /** Shows a finished mesh in place of the scene on screen. */
  private void upload(PreviewMesh mesh) {
    freeScene();
    try {
      PreviewScene uploaded = PreviewScene.upload(minecraft, mesh);
      uploaded.showLayers(shownLayers);
      scene = uploaded;
      renderState.scene(uploaded);
      setStatus(Status.READY);
    } catch (RuntimeException exception) {
      // The GPU refused the buffers (out of memory): the thumbnail stays.
      setStatus(Status.TOO_LARGE);
    }
  }

  private static Status failureStatus(@Nullable Throwable failure) {
    Throwable cause = failure;
    while (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof VolumeFormatException format) {
      return format.tooLarge() ? Status.TOO_LARGE : Status.FAILED_READ;
    }
    if (cause instanceof CancellationException) {
      return Status.NONE;
    }
    if (cause instanceof ApiException || cause instanceof IOException) {
      return Status.FAILED_DOWNLOAD;
    }
    // A renderer failing on an odd block: the file cannot be shown.
    return Status.FAILED_READ;
  }

  private void remember(String key, LoadedFile read) {
    files.put(key, read);
    long cells = 0;
    for (LoadedFile kept : files.values()) {
      cells += kept.volume().volume();
    }
    Iterator<LoadedFile> oldest = files.values().iterator();
    while (files.size() > 1 && (files.size() > MAX_VOLUMES || cells > MAX_CACHED_CELLS)) {
      cells -= oldest.next().volume().volume();
      oldest.remove();
    }
  }

  /**
   * The shown file's blocks by type with their replacements, once its 3D was built at least once;
   * null before (or for a design not shown in 3D). The same instance until something changes.
   */
  public @Nullable BlockList blockList() {
    LoadedFile shown = loaded;
    LoadedFile.EditedPalette palette = edited;
    if (shown == null || palette == null) {
      return null;
    }
    BlockList list = blockList;
    if (list == null) {
      list = shown.blockList(palette);
      blockList = list;
    }
    return list;
  }

  /**
   * {@link #blockList} counting only the layers shown: a block absent from them has no row. Null
   * like {@link #blockList}. The same instance until something changes; one pass over the range's
   * cells otherwise, so call it when the layers rest, not per frame or per layer of a drag.
   */
  public @Nullable BlockList shownBlockList() {
    LoadedFile shown = loaded;
    LoadedFile.EditedPalette palette = edited;
    if (shown == null || palette == null || shownLayers.covers(shown.volume().sizeY())) {
      return blockList();
    }
    BlockList list = rangeBlockList;
    if (list == null) {
      list = shown.blockList(palette, shownLayers);
      rangeBlockList = list;
    }
    return list;
  }

  /** Whether a rebuild after a change is still running (the previous 3D is on screen). */
  public boolean rebuilding() {
    return rebuilding;
  }

  /**
   * Replaces every state of {@code oldBlock} on the layers shown with {@code newBlock} (air removes
   * it): the whole design when every layer shows.
   */
  public void replace(String oldBlock, String newBlock) {
    LoadedFile.EditedPalette palette = edited;
    if (palette != null) {
      apply(palette.replacements().with(new BlockRule(oldBlock, newBlock, shownLayers)));
    }
  }

  /** Undoes one rule: the others stay. */
  public void restore(BlockRule rule) {
    LoadedFile.EditedPalette palette = edited;
    if (palette != null) {
      apply(palette.replacements().without(rule));
    }
  }

  /** The layers a replacement chosen now applies to: {@link LayerRange#ALL} for every layer. */
  public LayerRange replacementLayers() {
    return shownLayers;
  }

  /** Keeps every block as the file has it. */
  public void restoreAll() {
    apply(BlockReplacements.NONE);
  }

  /** Remembers the replacements for the shown file and rebuilds its mesh with them. */
  private void apply(BlockReplacements replacements) {
    Source current = source;
    LoadedFile shown = loaded;
    LoadedFile.EditedPalette palette = edited;
    if (current == null
        || shown == null
        || palette == null
        || palette.replacements().equals(replacements)) {
      return;
    }
    edits.put(current.designId(), current.fileId(), replacements);
    LoadedFile.EditedPalette next = shown.edited(replacements);
    edited = next;
    blockList = null;
    rangeBlockList = null;
    rebuilding = true;
    int build = generation.incrementAndGet();
    // The layers shown stay: the rebuild makes their cut mesh too, so no holes show meanwhile.
    LayerRange layers = shownLayers;
    boolean cut = !layers.covers(shown.volume().sizeY());
    int cutBuild = cutGeneration.incrementAndGet();
    pendingCutLayers = cut ? layers : null;
    PreviewMeshBuilder.Settings settings = PreviewMeshBuilder.Settings.capture(minecraft);
    CompletableFuture.supplyAsync(
            () -> {
              PreviewMesh whole = mesh(shown, next, settings, build);
              try {
                PreviewMesh cutPart =
                    cut ? cutMesh(shown, next, settings, layers, build, cutBuild) : null;
                return new Rebuilt(whole, cutPart, layers, cutBuild);
              } catch (RuntimeException exception) {
                whole.close();
                throw exception;
              }
            },
            PreviewExecutor.executor())
        .whenCompleteAsync((meshes, failure) -> rebuilt(build, meshes, failure), renderThreadLater);
  }

  /** Render thread: shows the newest rebuild, frees a stale one. */
  private void rebuilt(int build, @Nullable Rebuilt meshes, @Nullable Throwable failure) {
    if (build != generation.get()) {
      if (meshes != null) {
        meshes.close();
      }
      return;
    }
    rebuilding = false;
    if (meshes == null) {
      freeScene();
      setStatus(failureStatus(failure));
      return;
    }
    standIns = meshes.whole().standIns();
    upload(meshes.whole());
    PreviewMesh cutPart = meshes.cut();
    if (cutPart != null) {
      cutBuilt(build, meshes.cutBuild(), meshes.layers(), cutPart);
    }
    settleLayers();
  }

  /** The Y layers of the shown design; 0 while none is shown in 3D. */
  public int layerCount() {
    PreviewScene shown = scene;
    return shown == null ? 0 : shown.height();
  }

  /** The Y layers shown (the same across edits), inside {@link #layerCount}. */
  public LayerRange layerRange() {
    return shownLayers.clampedTo(layerCount());
  }

  /**
   * Shows {@code layers} at once, drawn as one run of the scene's mesh (while the slider is
   * dragged): no rebuild. {@link #settleLayers} makes it solid.
   */
  public void showLayers(LayerRange layers) {
    PreviewScene shown = scene;
    if (shown == null) {
      return;
    }
    LayerRange clamped = layers.clampedTo(shown.height());
    LayerRange next = clamped.covers(shown.height()) ? LayerRange.ALL : clamped;
    if (!next.equals(shownLayers)) {
      shownLayers = next;
      rangeBlockList = null;
      shown.showLayers(next);
    }
  }

  /**
   * The slider rests: builds the mesh cut to the layers shown on the worker (their run stays on
   * screen meanwhile), unless it is built or being built; all layers free the cut mesh.
   */
  public void settleLayers() {
    PreviewScene shown = scene;
    LoadedFile shownFile = loaded;
    LoadedFile.EditedPalette palette = edited;
    if (shown == null || shownFile == null || palette == null) {
      return;
    }
    LayerRange layers = shownLayers;
    if (layers.covers(shown.height())) {
      cutGeneration.incrementAndGet();
      pendingCutLayers = null;
      shown.releaseCut();
      return;
    }
    if (shown.hasCutFor(layers) || layers.equals(pendingCutLayers)) {
      return;
    }
    int build = generation.get();
    int cutBuild = cutGeneration.incrementAndGet();
    pendingCutLayers = layers;
    PreviewMeshBuilder.Settings settings = PreviewMeshBuilder.Settings.capture(minecraft);
    CompletableFuture.supplyAsync(
            () -> cutMesh(shownFile, palette, settings, layers, build, cutBuild),
            PreviewExecutor.executor())
        .whenCompleteAsync(
            (mesh, failure) -> {
              if (mesh != null) {
                cutBuilt(build, cutBuild, layers, mesh);
              } else if (cutBuild == cutGeneration.get()) {
                pendingCutLayers = null;
              }
            },
            renderThreadLater);
  }

  /** Shows the whole design again and frees the cut mesh. */
  public void showAllLayers() {
    showLayers(LayerRange.ALL);
    settleLayers();
  }

  /** Render thread: shows the newest cut mesh in the scene, frees a stale one. */
  private void cutBuilt(int build, int cutBuild, LayerRange layers, PreviewMesh mesh) {
    PreviewScene shown = scene;
    if (build != generation.get() || cutBuild != cutGeneration.get() || shown == null) {
      mesh.close();
      return;
    }
    pendingCutLayers = null;
    try {
      shown.attachCut(PreviewScene.uploadGeometry(minecraft, mesh), layers);
    } catch (RuntimeException exception) {
      // The GPU refused the buffers: the range stays drawn as a run of the whole mesh.
      shown.releaseCut();
    }
  }

  /** The format the shown file's edited copy is saved in; null before its first build. */
  public @Nullable SchematicFormat editedFormat() {
    LoadedFile shown = loaded;
    return shown == null ? null : shown.savedFormat();
  }

  /** Whether any block of the shown file is replaced. */
  public boolean edited() {
    LoadedFile.EditedPalette palette = edited;
    return palette != null && !palette.replacements().isEmpty();
  }

  /**
   * The shown file with its replacements, written on the worker from the bytes in memory.
   *
   * @param name the name the player gave it
   * @param author the design's authors
   * @return the file, or a failure when it cannot be written (damaged, nothing shown)
   */
  public CompletableFuture<EditedFile> editedFile(String name, String author) {
    LoadedFile shown = loaded;
    LoadedFile.EditedPalette palette = edited;
    Source target = source;
    if (shown == null || palette == null || target == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("no design shown"));
    }
    long now = System.currentTimeMillis();
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return EditedFiles.write(shown, palette, target.format(), name, author, now);
          } catch (VolumeFormatException | NbtFormatException exception) {
            throw new CompletionException(exception);
          }
        },
        PreviewExecutor.executor());
  }

  /** Sets a status a load reached by itself and tells the screen. */
  private void setStatus(Status next) {
    status = next;
    listener.run();
  }

  private void freeScene() {
    PreviewScene shown = scene;
    if (shown != null) {
      shown.close();
      scene = null;
    }
    renderState.scene(null);
  }

  private void forgetEditor() {
    loaded = null;
    edited = null;
    blockList = null;
    rangeBlockList = null;
    rebuilding = false;
  }

  /** Stops showing anything: frees the scene and drops any build in progress. */
  public void release() {
    generation.incrementAndGet();
    cutGeneration.incrementAndGet();
    pendingCutLayers = null;
    shownLayers = LayerRange.ALL;
    freeScene();
    forgetEditor();
    schematic = null;
    file = null;
    savedFile = null;
    localFile = null;
    localFormat = null;
    source = null;
    localBlocks = 0;
    allowOverLimit = false;
    standIns = NO_STAND_INS;
    status = Status.NONE;
  }

  /** Frees everything: the scene, builds in progress and the cached files. */
  @Override
  public void close() {
    release();
    files.clear();
    chosenFiles.clear();
    listener = () -> {};
  }

  /**
   * A parsed file, its palette with the replacements, and its mesh; no palette nor mesh for a local
   * file found over the block limit.
   */
  private record Built(
      LoadedFile file, LoadedFile.@Nullable EditedPalette palette, @Nullable PreviewMesh mesh) {}

  /**
   * What a load is for.
   *
   * @param key the file among the cached ones: its digest, or a local file's path, size and time
   * @param designId with {@code fileId}, whose replacements apply ({@link DesignEdits})
   * @param blocks the blocks the catalog gives, or {@link #UNKNOWN_BLOCKS}
   */
  private record Source(
      String key, String designId, String fileId, SchematicFormat format, long blocks) {}

  /** An edit's rebuild: the whole design's mesh and, when a cut is shown, the cut one. */
  private record Rebuilt(
      PreviewMesh whole, @Nullable PreviewMesh cut, LayerRange layers, int cutBuild)
      implements AutoCloseable {
    @Override
    public void close() {
      whole.close();
      if (cut != null) {
        cut.close();
      }
    }
  }
}
