package com.einsteinmods.schematicvault.client;

import com.einsteinmods.schematicvault.client.cache.CatalogCache;
import com.einsteinmods.schematicvault.client.cache.CatalogPrefetch;
import com.einsteinmods.schematicvault.client.cache.CatalogRepository;
import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.client.download.Downloader;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.catalog.ApiJson;
import com.einsteinmods.schematicvault.core.catalog.CatalogFormatException;
import com.einsteinmods.schematicvault.core.catalog.SchematicDetail;
import com.einsteinmods.schematicvault.core.config.VaultSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything one opening of the vault uses: the API client (online, or a local copy of the API when
 * {@code <game>/schematicvault/api/v1/manifest.json} exists), the catalog, thumbnails, design
 * records, the 3D preview, the block replacements chosen per design file ({@link DesignEdits}),
 * downloads and the bridge to Litematica ({@link LitematicaBridge}, made on first use). Created
 * when the vault opens and closed when it closes for good (not when one of its own sub-screens
 * opens): the textures, the preview's GPU buffers and its cached volumes are freed, the
 * replacements forgotten, and the HTTP client stops once the requests in flight (a download)
 * finish.
 */
public final class VaultSession implements AutoCloseable {
  private static final String DATA_FOLDER = "schematicvault";
  private static final String CACHE_FOLDER = "cache";
  private static final String LOCAL_API_FOLDER = "api";
  private static final String THUMBNAIL_FOLDER = "thumbnails";
  private static final String SCHEMATICS_FOLDER = "schematics";
  private static final String USER_AGENT_PREFIX = "SchematicVault/";
  private static final int MAX_DETAIL_BYTES = 4 * 1024 * 1024;

  private static final Logger LOGGER = LoggerFactory.getLogger(VaultSession.class);

  /** Design records kept while the vault is open (about 10 KB each). */
  private static final int MAX_DETAILS = 32;

  private final VaultSettings settings;
  private final ApiClient api;
  private final CatalogRepository catalog;
  private final ThumbnailCache thumbnails;
  private final Downloader downloader;
  private final PreviewController previews;
  private final DesignEdits edits;
  private final Map<String, DetailView> details = new LinkedHashMap<>(16, 0.75F, true);
  private @Nullable LitematicaBridge litematica;

  private VaultSession(
      VaultSettings settings,
      ApiClient api,
      CatalogRepository catalog,
      ThumbnailCache thumbnails,
      Downloader downloader,
      PreviewController previews,
      DesignEdits edits) {
    this.settings = settings;
    this.api = api;
    this.catalog = catalog;
    this.thumbnails = thumbnails;
    this.downloader = downloader;
    this.previews = previews;
    this.edits = edits;
  }

  public static VaultSession open(Minecraft minecraft, VaultSettings settings) {
    FabricLoader loader = FabricLoader.getInstance();
    Path gameDirectory = loader.getGameDir();
    Path cacheDirectory = cacheDirectory();
    Path localCopy = localApiCopy(gameDirectory);
    ApiClient api =
        localCopy != null
            ? ApiClient.localCopy(localCopy)
            : new ApiClient(settings.apiBaseUrl(), userAgent());
    DesignEdits edits = new DesignEdits();
    CatalogPrefetch.sessionOpened();
    return new VaultSession(
        settings,
        api,
        new CatalogRepository(
            api, new CatalogCache(cacheDirectory, api.cacheKey(settings.apiBaseUrl())), minecraft),
        new ThumbnailCache(api, thumbnailDirectory(), minecraft.getTextureManager(), minecraft),
        new Downloader(
            api, gameDirectory.resolve(SCHEMATICS_FOLDER).resolve(settings.downloadFolder())),
        new PreviewController(minecraft, api, settings.previewBlockLimit(), edits),
        edits);
  }

  /**
   * The real path of {@code <game>/schematicvault/api/} when it holds a copy of the API (its {@code
   * v1/manifest.json} exists), else null: one disk check per open.
   */
  public static @Nullable Path localApiCopy(Path gameDirectory) {
    Path folder = gameDirectory.resolve(DATA_FOLDER).resolve(LOCAL_API_FOLDER);
    if (!Files.isRegularFile(folder.resolve(CatalogRepository.MANIFEST_PATH))) {
      return null;
    }
    try {
      return folder.toRealPath();
    } catch (IOException exception) {
      LOGGER.warn("Local API copy {} is unreadable; using the online API", folder, exception);
      return null;
    }
  }

  /** The {@code User-Agent} of the mod's requests, e.g. {@code SchematicVault/0.1.0}. */
  public static String userAgent() {
    String version =
        FabricLoader.getInstance()
            .getModContainer(SchematicVaultClient.MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("");
    return USER_AGENT_PREFIX + version;
  }

  /** The folder of the mod's caches. */
  public static Path cacheDirectory() {
    return FabricLoader.getInstance().getGameDir().resolve(DATA_FOLDER).resolve(CACHE_FOLDER);
  }

  /** The folder of the API's thumbnails ({@link ThumbnailCache}). */
  public static Path thumbnailDirectory() {
    return cacheDirectory().resolve(THUMBNAIL_FOLDER);
  }

  public VaultSettings settings() {
    return settings;
  }

  public CatalogRepository catalog() {
    return catalog;
  }

  public ThumbnailCache thumbnails() {
    return thumbnails;
  }

  public Downloader downloader() {
    return downloader;
  }

  public PreviewController previews() {
    return previews;
  }

  /**
   * The bridge to Litematica, made on first use: the no-op without Litematica. A failure inside
   * Litematica turns it off until the vault opens again.
   */
  public LitematicaBridge litematica() {
    LitematicaBridge bridge = litematica;
    if (bridge == null) {
      bridge = LitematicaBridge.create();
      litematica = bridge;
    }
    return bridge;
  }

  /** A design record fetched earlier in this session, or null. Render thread. */
  public @Nullable DetailView cachedDetail(String id) {
    return details.get(id);
  }

  /**
   * Fetches a design's record and checks which of its files are already saved; completes on the I/O
   * pool. Call {@link #rememberDetail} on the render thread with the result.
   */
  public CompletableFuture<DetailView> loadDetail(String id) {
    String path = catalog.schematicPath(id);
    if (path == null) {
      return CompletableFuture.failedFuture(
          new ApiException(FailureKind.SERVER, "unsafe design id " + id));
    }
    return api.fetch(path, MAX_DETAIL_BYTES)
        .thenApplyAsync(
            bytes -> {
              SchematicDetail detail;
              try {
                detail = ApiJson.detail(new String(bytes, StandardCharsets.UTF_8));
              } catch (CatalogFormatException exception) {
                throw new ApiException(FailureKind.SERVER, exception.getMessage(), exception);
              }
              return new DetailView(detail, downloader.existing(detail.files()));
            },
            ApiClient.executor());
  }

  /** Keeps a fetched record for this session, dropping the least recently viewed past 32. */
  public void rememberDetail(DetailView view) {
    details.put(view.detail().id(), view);
    if (details.size() > MAX_DETAILS) {
      String oldest = details.keySet().iterator().next();
      details.remove(oldest);
    }
  }

  @Override
  public void close() {
    CatalogPrefetch.sessionClosed();
    catalog.close();
    thumbnails.release();
    previews.close();
    edits.clear();
    api.close();
  }

  /**
   * A design's record and where its files are already saved.
   *
   * @param saved saved file by file id
   */
  public record DetailView(SchematicDetail detail, Map<String, Path> saved) {
    public DetailView {
      saved = Map.copyOf(saved);
    }
  }
}
