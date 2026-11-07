package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.core.catalog.ApiJson;
import com.einsteinmods.schematicvault.core.catalog.ApiLanguages;
import com.einsteinmods.schematicvault.core.catalog.Catalog;
import com.einsteinmods.schematicvault.core.catalog.CatalogFormatException;
import com.einsteinmods.schematicvault.core.catalog.CategoryNode;
import com.einsteinmods.schematicvault.core.catalog.IndexEndpoint;
import com.einsteinmods.schematicvault.core.catalog.IndexPartJson;
import com.einsteinmods.schematicvault.core.catalog.LocaleTexts;
import com.einsteinmods.schematicvault.core.catalog.Manifest;
import com.einsteinmods.schematicvault.core.catalog.RefreshPlan;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.core.net.ShardRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the catalog: the disk cache is read first and shown at once, then only {@code
 * manifest.json} is fetched; the designs are downloaded again only when its revision differs from
 * the cache's (all of them: the API has no search, so the whole catalog is what the mod filters),
 * from the compact index when the manifest announces one, else from the pages; the locale (titles,
 * tag and category names) alone when only the game language changed. Downloads at most {@link
 * #PARALLEL_PAGES} pages or index parts at a time. All parsing and indexing runs on the I/O pool;
 * results reach the listener on the render thread.
 */
public final class CatalogRepository {
  public static final String MANIFEST_PATH = "v1/manifest.json";

  /**
   * Pages in flight (one HTTP/2 connection multiplexes them). Measured on Cloudflare: 603 pages in
   * about 15 s with 4, 4.8 s with 16; gunzip and parse take 0.8 ms per page on the I/O pool, so its
   * two threads keep up.
   */
  private static final int PARALLEL_PAGES = 16;

  private static final int MAX_MANIFEST_BYTES = 256 * 1024;
  private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;

  /** An index part of 20,000 rows is about 8.5 MB (3.1 MB gzipped): room to grow fourfold. */
  private static final int MAX_INDEX_PART_BYTES = 32 * 1024 * 1024;

  private static final int MAX_TAXONOMY_BYTES = 2 * 1024 * 1024;
  private static final int MAX_LOCALE_BYTES = 16 * 1024 * 1024;

  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogRepository.class);
  private static final Progress NO_PROGRESS = (done, total) -> {};

  private final ApiClient api;
  private final CatalogCache cache;
  private final Executor renderThread;
  private volatile boolean closed;

  /** The design record path template from the last manifest; the default until one is read. */
  private volatile String schematicTemplate = ApiJson.DEFAULT_SCHEMATIC_TEMPLATE;

  public CatalogRepository(ApiClient api, CatalogCache cache, Executor renderThread) {
    this.api = api;
    this.cache = cache;
    this.renderThread = renderThread;
  }

  /** The shards' roots from the last manifest; empty until one is read or without shards. */
  private volatile List<String> shardRoots = List.of();

  /**
   * Address of a design's record (on its shard when the API has shards), or null for an id that
   * cannot be part of a safe path.
   */
  public @Nullable String schematicPath(String id) {
    String path = RemotePaths.fill(schematicTemplate, ApiJson.ID_PLACEHOLDER, id).orElse(null);
    List<String> roots = shardRoots;
    return path == null || roots.isEmpty()
        ? path
        : roots.get(ShardRule.shardOf(id, roots.size())) + path;
  }

  /**
   * Reads the cache, then refreshes it; see {@link CatalogListener} for what is reported. A
   * background refresh in flight ({@link CatalogPrefetch}) is awaited first, so its cache is read
   * and nothing is downloaded twice.
   */
  public void open(String minecraftLanguage, CatalogListener listener) {
    CatalogPrefetch.pending()
        .thenApplyAsync(ignored -> cache.read().map(LoadedCatalog::of), ApiClient.executor())
        .whenComplete(
            (cached, failure) -> {
              LoadedCatalog loaded = cached == null ? null : cached.orElse(null);
              post(() -> listener.onCacheRead(loaded));
              refresh(loaded == null ? null : loaded.catalog(), minecraftLanguage, listener);
            });
  }

  /** Fetches the manifest and whatever it says changed; for Retry after a failure. */
  public void refresh(
      @Nullable Catalog cached, String minecraftLanguage, CatalogListener listener) {
    api.fetch(MANIFEST_PATH, MAX_MANIFEST_BYTES)
        .thenApply(bytes -> parse(bytes, ApiJson::manifest))
        .thenCompose(manifest -> update(cached, manifest, minecraftLanguage, listener))
        .whenComplete(
            (updated, failure) -> {
              if (failure != null) {
                LOGGER.warn("Could not refresh the schematic catalog: {}", failure.toString());
                FailureKind kind = ApiException.kindOf(failure);
                post(() -> listener.onFailed(kind));
              } else if (updated == null) {
                post(listener::onCurrent);
              } else {
                post(() -> listener.onUpdated(updated));
              }
            });
  }

  /**
   * Without a vault open: fetches the manifest and, when the cache's header ({@link
   * CatalogCache#readHeader}) shows another revision or language, every page, then writes the
   * cache. Completes with nothing, so the catalog is freed once written; no progress is reported.
   */
  public CompletableFuture<Void> refreshCache(String minecraftLanguage) {
    return api.fetch(MANIFEST_PATH, MAX_MANIFEST_BYTES)
        .thenApply(bytes -> parse(bytes, ApiJson::manifest))
        .thenCompose(
            manifest -> {
              String language = ApiLanguages.choose(minecraftLanguage, manifest.languages());
              RefreshPlan plan =
                  RefreshPlan.fromHeader(
                      cache.readHeader().orElse(null), manifest.revision(), language);
              return plan == RefreshPlan.UP_TO_DATE
                  ? CompletableFuture.completedFuture(null)
                  : downloadAll(manifest, language, NO_PROGRESS)
                      .thenAcceptAsync(this::writeOrFail, ApiClient.executor());
            });
  }

  /** Stops reporting to the listener (the screen closed); requests in flight just finish. */
  public void close() {
    closed = true;
  }

  /** The new catalog, or null when the cache is current. */
  private CompletableFuture<@Nullable LoadedCatalog> update(
      @Nullable Catalog cached,
      Manifest manifest,
      String minecraftLanguage,
      CatalogListener listener) {
    schematicTemplate = manifest.schematicTemplate();
    api.trustShards(manifest.shardRoots());
    shardRoots = manifest.shardRoots();
    String language = ApiLanguages.choose(minecraftLanguage, manifest.languages());
    RefreshPlan plan = RefreshPlan.decide(cached, manifest.revision(), language);
    if (plan == RefreshPlan.UP_TO_DATE) {
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Catalog> catalog =
        plan == RefreshPlan.LOCALE_ONLY && cached != null
            ? locale(manifest, language).thenApply(cached::withLocale)
            : downloadAll(manifest, language, progressTo(listener));
    return catalog.thenApplyAsync(this::store, ApiClient.executor());
  }

  private CompletableFuture<Catalog> downloadAll(
      Manifest manifest, String language, Progress progress) {
    CompletableFuture<List<CategoryNode>> taxonomy =
        api.fetch(manifest.taxonomyPath(), MAX_TAXONOMY_BYTES)
            .thenApply(bytes -> parse(bytes, ApiJson::taxonomy));
    CompletableFuture<LocaleTexts> locale = locale(manifest, language);
    CompletableFuture<List<SchematicSummary>> schematics = schematics(manifest, progress);
    return taxonomy.thenCombine(
        locale.thenCombine(schematics, Pages::new),
        (nodes, pages) ->
            new Catalog(
                manifest.revision(),
                manifest.generatedAt(),
                pages.schematics(),
                nodes,
                pages.locale()));
  }

  /**
   * The locale's titles, tag and category names, English included (it translates titles written in
   * other languages). When the locale cannot be read the original texts are shown, so a missing
   * language never blocks the catalog; {@link LocaleTexts#NONE} matches no language, so the next
   * open asks for it again.
   */
  private CompletableFuture<LocaleTexts> locale(Manifest manifest, String language) {
    String path =
        RemotePaths.fill(manifest.localeTemplate(), ApiJson.LANGUAGE_PLACEHOLDER, language)
            .orElse(null);
    if (path == null) {
      return CompletableFuture.completedFuture(LocaleTexts.NONE);
    }
    return api.fetch(path, MAX_LOCALE_BYTES)
        .thenApply(bytes -> parse(bytes, json -> ApiJson.locale(language, json)))
        .exceptionally(
            failure -> {
              LOGGER.warn("No locale texts for {}: {}", language, failure.toString());
              return LocaleTexts.NONE;
            });
  }

  /**
   * Every design: from the compact index when the manifest announces one, else from the pages. An
   * index part missing (HTTP 404, or a local copy without the index) falls back to the pages.
   */
  private CompletableFuture<List<SchematicSummary>> schematics(
      Manifest manifest, Progress progress) {
    IndexEndpoint index = manifest.index();
    if (index == null) {
      return pages(manifest, progress);
    }
    return indexParts(manifest, index, progress)
        .exceptionallyCompose(
            failure -> {
              if (!ApiException.isNotFound(failure)) {
                return CompletableFuture.failedFuture(failure);
              }
              LOGGER.warn("No catalog index, reading the pages: {}", failure.toString());
              return pages(manifest, progress);
            });
  }

  /** Every index part, at most {@link #PARALLEL_PAGES} in flight, joined in part order. */
  private CompletableFuture<List<SchematicSummary>> indexParts(
      Manifest manifest, IndexEndpoint index, Progress progress) {
    List<String> shardRoots = manifest.shardRoots();
    return inParallel(
        index.partCount(),
        part ->
            RemotePaths.fill(index.template(), ApiJson.PART_PLACEHOLDER, Integer.toString(part))
                .orElse(null),
        MAX_INDEX_PART_BYTES,
        (part, bytes) -> IndexPartJson.read(bytes, part, index.partCount(), shardRoots),
        progress);
  }

  /** Every page, at most {@link #PARALLEL_PAGES} in flight, joined in page order. */
  private CompletableFuture<List<SchematicSummary>> pages(Manifest manifest, Progress progress) {
    return inParallel(
        manifest.pageCount(),
        page ->
            RemotePaths.fill(
                    manifest.pageTemplate(), ApiJson.PAGE_PLACEHOLDER, Integer.toString(page))
                .orElse(null),
        MAX_PAGE_BYTES,
        (page, bytes) -> parse(bytes, ApiJson::page),
        progress);
  }

  /**
   * Fetches and reads resources {@code 1..count}, at most {@link #PARALLEL_PAGES} in flight,
   * reporting each one done; joins their designs in resource order.
   */
  private CompletableFuture<List<SchematicSummary>> inParallel(
      int count, PathOf pathOf, int maxBytes, PartReader reader, Progress progress) {
    AtomicReferenceArray<List<SchematicSummary>> parts = new AtomicReferenceArray<>(count);
    Batch batch = new Batch(count, pathOf, maxBytes, reader, progress, parts);
    progress.report(0, count);
    List<CompletableFuture<Void>> workers = new ArrayList<>(PARALLEL_PAGES);
    for (int worker = 0; worker < Math.min(PARALLEL_PAGES, count); worker++) {
      workers.add(next(batch));
    }
    return CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new))
        .thenApply(
            ignored -> {
              List<SchematicSummary> all = new ArrayList<>();
              for (int part = 0; part < count; part++) {
                all.addAll(parts.get(part));
              }
              return all;
            });
  }

  private CompletableFuture<Void> next(Batch batch) {
    int part = batch.next.getAndIncrement();
    if (part > batch.count) {
      return CompletableFuture.completedFuture(null);
    }
    String path = batch.pathOf.path(part);
    if (path == null) {
      return CompletableFuture.failedFuture(
          new ApiException(FailureKind.SERVER, "unsafe catalog template"));
    }
    return api.fetch(path, batch.maxBytes)
        .thenApply(bytes -> read(batch.reader, part, bytes))
        .thenCompose(
            schematics -> {
              batch.parts.set(part - 1, schematics);
              batch.progress.report(batch.done.incrementAndGet(), batch.count);
              return next(batch);
            });
  }

  private static List<SchematicSummary> read(PartReader reader, int part, byte[] bytes) {
    try {
      return reader.read(part, bytes);
    } catch (CatalogFormatException exception) {
      throw new ApiException(FailureKind.SERVER, exception.getMessage(), exception);
    }
  }

  /** Writes the cache, then indexes the catalog for the screen (I/O pool). */
  private LoadedCatalog store(Catalog catalog) {
    try {
      cache.write(catalog);
    } catch (IOException exception) {
      // The catalog is still shown; the next open downloads it again.
      LOGGER.warn("Could not write the catalog cache", exception);
    }
    return LoadedCatalog.of(catalog);
  }

  /** Writes the cache for {@link #refreshCache}: a failure fails the refresh (I/O pool). */
  private void writeOrFail(Catalog catalog) {
    try {
      cache.write(catalog);
    } catch (IOException exception) {
      throw new CompletionException(exception);
    }
  }

  private Progress progressTo(CatalogListener listener) {
    return (done, total) -> post(() -> listener.onProgress(done, total));
  }

  private void post(Runnable report) {
    renderThread.execute(
        () -> {
          if (!closed) {
            report.run();
          }
        });
  }

  private static <T> T parse(byte[] bytes, JsonReader<T> reader) {
    try {
      return reader.read(new String(bytes, StandardCharsets.UTF_8));
    } catch (CatalogFormatException exception) {
      throw new ApiException(FailureKind.SERVER, exception.getMessage(), exception);
    }
  }

  @FunctionalInterface
  private interface JsonReader<T> {
    T read(String json) throws CatalogFormatException;
  }

  /** The address of resource {@code part} (from 1), or null when the template is unsafe. */
  @FunctionalInterface
  private interface PathOf {
    @Nullable String path(int part);
  }

  /** Reads the designs of resource {@code part} (from 1) from its body. */
  @FunctionalInterface
  private interface PartReader {
    List<SchematicSummary> read(int part, byte[] bytes) throws CatalogFormatException;
  }

  /** One {@link #inParallel} run: what its workers share. */
  private record Batch(
      int count,
      PathOf pathOf,
      int maxBytes,
      PartReader reader,
      Progress progress,
      AtomicReferenceArray<List<SchematicSummary>> parts,
      AtomicInteger next,
      AtomicInteger done) {
    private Batch(
        int count,
        PathOf pathOf,
        int maxBytes,
        PartReader reader,
        Progress progress,
        AtomicReferenceArray<List<SchematicSummary>> parts) {
      this(
          count,
          pathOf,
          maxBytes,
          reader,
          progress,
          parts,
          new AtomicInteger(1),
          new AtomicInteger());
    }
  }

  /** Resources done of the total, from the I/O pool. */
  @FunctionalInterface
  private interface Progress {
    void report(int done, int total);
  }

  private record Pages(LocaleTexts locale, List<SchematicSummary> schematics) {}
}
