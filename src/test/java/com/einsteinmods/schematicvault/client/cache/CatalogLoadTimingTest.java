package com.einsteinmods.schematicvault.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.client.net.LimitedReads;
import com.einsteinmods.schematicvault.core.catalog.ApiJson;
import com.einsteinmods.schematicvault.core.catalog.CatalogFormatException;
import com.einsteinmods.schematicvault.core.catalog.Manifest;
import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dev-only timings of loading the whole catalog, read from the API's local copy; skipped where that
 * folder does not exist (CI, the public repo). Reported as test entries, never shipped.
 */
class CatalogLoadTimingTest {
  private static final Path API_FOLDER = LocalApiFiles.apiFolder();

  private static final String MANIFEST_PATH = "v1/manifest.json";
  private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
  private static final String ONLINE = "https://example.org/";
  private static final String LANGUAGE = "es_es";

  /**
   * What the I/O pool does per downloaded page (gunzip, then parse): its total bounds how many
   * pages in flight two threads keep up with.
   */
  @Test
  void pageDecodeAndParseCost(TestReporter reporter) throws IOException, CatalogFormatException {
    assumeTrue(Files.isRegularFile(API_FOLDER.resolve(MANIFEST_PATH)), "no local API copy");
    Manifest manifest = ApiJson.manifest(Files.readString(API_FOLDER.resolve(MANIFEST_PATH)));
    List<byte[]> gzipped = new ArrayList<>();
    long compressedBytes = 0;
    for (int page = 1; page <= manifest.pageCount(); page++) {
      String path =
          RemotePaths.fill(
                  manifest.pageTemplate(), ApiJson.PAGE_PLACEHOLDER, Integer.toString(page))
              .orElseThrow();
      byte[] compressed = gzip(Files.readAllBytes(API_FOLDER.resolve(path)));
      compressedBytes += compressed.length;
      gzipped.add(compressed);
    }
    int designs = 0;
    long start = System.nanoTime();
    for (byte[] compressed : gzipped) {
      try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
        String json =
            new String(LimitedReads.readAll(input, MAX_PAGE_BYTES), StandardCharsets.UTF_8);
        designs += ApiJson.page(json).size();
      }
    }
    long elapsed = System.nanoTime() - start;
    reporter.publishEntry("pages", Integer.toString(gzipped.size()));
    reporter.publishEntry(
        "gzip KB per page (mean)",
        String.format(Locale.ROOT, "%.1f", compressedBytes / 1024.0 / gzipped.size()));
    reporter.publishEntry("gunzip+parse ms, all pages, one thread", millis(elapsed));
    reporter.publishEntry("gunzip+parse ms per page (mean)", millis(elapsed / gzipped.size()));
    reporter.publishEntry("designs", Integer.toString(designs));
    assertTrue(designs > 0);
  }

  /**
   * A vault open on a local copy of the API: first with no cache (every page read, parsed, indexed
   * and cached), then again with that cache (cache read and index, manifest only).
   */
  @Test
  void localCopyOpens(TestReporter reporter, @TempDir Path cacheDirectory) throws IOException {
    assumeTrue(Files.isRegularFile(API_FOLDER.resolve(MANIFEST_PATH)), "no local API copy");
    ApiClient api = ApiClient.localCopy(API_FOLDER.toRealPath());
    CatalogCache cache = new CatalogCache(cacheDirectory, api.cacheKey(ONLINE));
    long start = System.nanoTime();
    Outcome first = open(new CatalogRepository(api, cache, Runnable::run));
    long firstNanos = System.nanoTime() - start;
    assertTrue(first.updated() != null, "first open downloads");
    start = System.nanoTime();
    Outcome second = open(new CatalogRepository(api, cache, Runnable::run));
    long secondNanos = System.nanoTime() - start;
    assertTrue(second.cached() != null && second.updated() == null, "second open is current");
    reporter.publishEntry("designs", Integer.toString(first.updated().index().size()));
    reporter.publishEntry("first open ms (read+parse+index+cache write)", millis(firstNanos));
    reporter.publishEntry("second open ms (cache read+index, manifest)", millis(secondNanos));
    reporter.publishEntry(
        "cache MB",
        String.format(
            Locale.ROOT,
            "%.1f",
            Files.size(cacheDirectory.resolve(CatalogCache.FILE_NAME)) / BYTES_PER_MEGABYTE));
  }

  /**
   * The background refresh on joining a world, on a local copy: with no cache every page is read
   * and the cache written; then the cache's header alone says it is current.
   */
  @Test
  void backgroundRefreshReadsOnlyTheHeaderWhenCurrent(
      TestReporter reporter, @TempDir Path cacheDirectory) throws IOException {
    assumeTrue(Files.isRegularFile(API_FOLDER.resolve(MANIFEST_PATH)), "no local API copy");
    ApiClient api = ApiClient.localCopy(API_FOLDER.toRealPath());
    CatalogCache cache = new CatalogCache(cacheDirectory, api.cacheKey(ONLINE));
    CatalogRepository repository = new CatalogRepository(api, cache, Runnable::run);
    long start = System.nanoTime();
    repository.refreshCache(LANGUAGE).join();
    long fullNanos = System.nanoTime() - start;
    Path file = cacheDirectory.resolve(CatalogCache.FILE_NAME);
    FileTime written = Files.getLastModifiedTime(file);
    start = System.nanoTime();
    repository.refreshCache(LANGUAGE).join();
    long currentNanos = System.nanoTime() - start;
    assertEquals(written, Files.getLastModifiedTime(file), "a current cache is not rewritten");
    start = System.nanoTime();
    assertTrue(cache.readHeader().isPresent());
    long headerNanos = System.nanoTime() - start;
    start = System.nanoTime();
    assertTrue(cache.read().isPresent());
    long readNanos = System.nanoTime() - start;
    reporter.publishEntry("background refresh ms, no cache (read+parse+write)", millis(fullNanos));
    reporter.publishEntry("background refresh ms, current cache", millis(currentNanos));
    reporter.publishEntry("cache header read ms", millis(headerNanos));
    reporter.publishEntry("whole cache read ms (for comparison)", millis(readNanos));
  }

  private static Outcome open(CatalogRepository repository) {
    CompletableFuture<Outcome> done = new CompletableFuture<>();
    LoadedCatalog[] cached = new LoadedCatalog[1];
    repository.open(
        LANGUAGE,
        new CatalogListener() {
          @Override
          public void onCacheRead(@Nullable LoadedCatalog catalog) {
            cached[0] = catalog;
          }

          @Override
          public void onProgress(int pagesDone, int total) {}

          @Override
          public void onCurrent() {
            done.complete(new Outcome(cached[0], null));
          }

          @Override
          public void onUpdated(LoadedCatalog catalog) {
            done.complete(new Outcome(cached[0], catalog));
          }

          @Override
          public void onFailed(FailureKind kind) {
            done.completeExceptionally(new IllegalStateException(kind.toString()));
          }
        });
    return done.join();
  }

  private record Outcome(@Nullable LoadedCatalog cached, @Nullable LoadedCatalog updated) {}

  private static byte[] gzip(byte[] bytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(bytes);
    }
    return output.toByteArray();
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI);
  }
}
