package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only checks and timings over the real catalog, read from the API's local copy; skipped where
 * that folder does not exist (CI, the public repo). Timings are reported as test entries, never
 * shipped.
 */
class RealCatalogTimingTest {
  private static final String API = Samples.API;

  private static final Path API_FOLDER = LocalApiFiles.apiFolder();

  private static final int WARMUP_ROUNDS = 20;
  private static final int TIMED_ROUNDS = 50;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double NANOS_PER_MICRO = 1_000.0;

  /** Categories whose featured designs are reported; empty is the whole catalog. */
  private static final String[] SAMPLE_CATEGORIES = {
    "", "farms/mob/iron", "technical/storage", "builds"
  };

  /**
   * Render-thread budget per typed letter: handing the search to {@link CatalogSearch} and taking
   * its result back (showing the rows is the list's own cost, unchanged).
   */
  private static final double MAX_RENDER_THREAD_MILLIS = 0.1;

  /** Budget for a typed letter's matches on the search thread, typing on (refined), median. */
  private static final double MAX_REFINED_MILLIS = 5.0;

  /** A word typed letter by letter, from an empty search box. */
  private static final String TYPED = "iron farm";

  /** Budget for one change of a category or fine filter (a click). */
  private static final double MAX_CHANGE_MILLIS = 1.0;

  private static final String FACETS_PATH = "v1/facets/";
  private static final String FACET_EXTENSION = ".json";
  private static final String FACET_VALUES = "values";
  private static final String FACET_VALUE = "value";
  private static final String FACET_COUNT = "count";
  private static final String VERIFIED_TRUE = "true";

  private static final String LANGUAGE = "es";
  private static final String LOCALE_PATH = "v1/locales/es.json";
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
  private static final long RESULT_WAIT_SECONDS = 5;

  private static Manifest manifest;
  private static Catalog catalog;
  private static long pagesParseNanos;

  @BeforeAll
  static void readCatalog() throws IOException, CatalogFormatException {
    assumeTrue(Files.isDirectory(API_FOLDER.resolve("v1")), "no local API copy");
    manifest = ApiJson.manifest(read("v1/manifest.json"));
    List<String> pages = new ArrayList<>();
    for (int page = 1; page <= manifest.pageCount(); page++) {
      pages.add(
          read(
              RemotePaths.fill(
                      manifest.pageTemplate(), ApiJson.PAGE_PLACEHOLDER, Integer.toString(page))
                  .orElseThrow()));
    }
    long start = System.nanoTime();
    List<SchematicSummary> schematics = new ArrayList<>();
    for (String page : pages) {
      schematics.addAll(ApiJson.page(page));
    }
    pagesParseNanos = System.nanoTime() - start;
    catalog =
        new Catalog(
            manifest.revision(),
            manifest.generatedAt(),
            schematics,
            ApiJson.taxonomy(read(manifest.taxonomyPath())),
            ApiJson.locale(LANGUAGE, read(LOCALE_PATH)));
  }

  @Test
  void everyCategoryFiltersToItsTaxonomyCount(TestReporter reporter) {
    reporter.publishEntry("pages parse ms", millis(pagesParseNanos));
    CatalogIndex index = CatalogIndex.of(catalog.schematics(), catalog.locale());
    int total = 0;
    for (CategoryNode node : catalog.taxonomy()) {
      total += node.count();
    }
    assertEquals(total, index.filter(CatalogFilter.NONE).length);
    assertCounts(index, catalog.taxonomy());
    assertTrue(index.filter(CatalogFilter.NONE.withText("iron")).length > 0);
  }

  /** Each fine filter alone gives the API's own facet counts ({@code v1/facets/*.json}). */
  @Test
  void fineFiltersGiveTheFacetCounts(TestReporter reporter) throws IOException {
    CatalogIndex index = CatalogIndex.of(catalog.schematics(), catalog.locale());
    Map<String, Integer> difficulty = facet("difficulty");
    for (Difficulty value : Difficulty.values()) {
      int found = index.filter(CatalogFilter.NONE.toggled(value)).length;
      reporter.publishEntry("difficulty " + value.id(), Integer.toString(found));
      assertEquals(difficulty.get(value.id()), found, value.id());
    }
    int verified = index.filter(CatalogFilter.NONE.withVerifiedOnly(true)).length;
    reporter.publishEntry("verified only", Integer.toString(verified));
    assertEquals(facet("verified").get(VERIFIED_TRUE), verified);
    Map<String, Integer> width = facet("width");
    for (WidthBucket value : WidthBucket.values()) {
      int found = index.filter(CatalogFilter.NONE.toggled(value)).length;
      assertEquals(width.get(value.label()), found, value.label());
    }
    int sizes = 0;
    for (SizeBucket value : SizeBucket.values()) {
      int found = index.filter(CatalogFilter.NONE.toggled(value)).length;
      reporter.publishEntry("size " + value, Integer.toString(found));
      sizes += found;
    }
    assertEquals(index.size(), sizes);
  }

  /** Clicks in the dropdowns and chips: one filter change each, over the whole catalog. */
  @Test
  void filterChangesFitTheClickBudget(TestReporter reporter) {
    CatalogIndex index = CatalogIndex.of(catalog.schematics(), catalog.locale());
    CatalogFilter farms = CatalogFilter.NONE.withCategory("farms");
    CatalogFilter[] changes = {
      CatalogFilter.NONE.toggled(Difficulty.HARD),
      CatalogFilter.NONE.toggled(Difficulty.HARD).toggled(Difficulty.EASY),
      CatalogFilter.NONE.withVerifiedOnly(true),
      CatalogFilter.NONE.toggled(SizeBucket.SMALL),
      CatalogFilter.NONE.toggled(WidthBucket.ONE).toggled(WidthBucket.TWO),
      farms,
      farms.toggled(SizeBucket.LARGE).toggled(Difficulty.MEDIUM).withSort(SortOrder.LARGEST),
      farms.withText("iron").toggled(WidthBucket.ELEVEN_OR_MORE),
      CatalogFilter.NONE.withSort(SortOrder.SMALLEST),
      CatalogFilter.NONE
    };
    long[] timings = new long[TIMED_ROUNDS * changes.length];
    int timed = 0;
    for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
      for (CatalogFilter filter : changes) {
        long start = System.nanoTime();
        index.filter(filter);
        long elapsed = System.nanoTime() - start;
        if (round >= WARMUP_ROUNDS) {
          timings[timed++] = elapsed;
        }
      }
    }
    long[] sorted = timings.clone();
    Arrays.sort(sorted);
    reporter.publishEntry("filter ms per change (median)", millis(median(timings)));
    reporter.publishEntry("filter ms per change (max)", millis(sorted[sorted.length - 1]));
    double medianMillis = median(timings) / NANOS_PER_MILLI;
    assertTrue(medianMillis < MAX_CHANGE_MILLIS, "median change " + medianMillis + " ms");
  }

  /** Featured picks over a whole filter result, the largest one being every design. */
  @Test
  void featuredFitsItsBudgetAndPrintsSampleTrios(TestReporter reporter) {
    CatalogIndex index = CatalogIndex.of(catalog.schematics(), catalog.locale());
    int[] everyDesign = index.filter(CatalogFilter.NONE);
    long[] timings = new long[TIMED_ROUNDS];
    int picked = 0;
    for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
      long start = System.nanoTime();
      picked += index.featured(everyDesign).length;
      long elapsed = System.nanoTime() - start;
      if (round >= WARMUP_ROUNDS) {
        timings[round - WARMUP_ROUNDS] = elapsed;
      }
    }
    assertEquals((WARMUP_ROUNDS + TIMED_ROUNDS) * CatalogIndex.FEATURED_COUNT, picked);
    long[] sorted = timings.clone();
    Arrays.sort(sorted);
    double medianMicros = median(timings) / NANOS_PER_MICRO;
    reporter.publishEntry(
        "featured us over every design (median)", String.format(Locale.ROOT, "%.1f", medianMicros));
    reporter.publishEntry(
        "featured us over every design (max)",
        String.format(Locale.ROOT, "%.1f", sorted[sorted.length - 1] / NANOS_PER_MICRO));
    assertTrue(median(timings) / NANOS_PER_MILLI < MAX_CHANGE_MILLIS, medianMicros + " us");
    for (String category : SAMPLE_CATEGORIES) {
      StringBuilder trio = new StringBuilder();
      for (int pick : index.featured(index.filter(CatalogFilter.NONE.withCategory(category)))) {
        SchematicSummary design = index.get(pick);
        trio.append(
            String.format(
                Locale.ROOT,
                "[%s %d %s] ",
                design.difficulty(),
                index.score(pick),
                index.title(pick)));
      }
      reporter.publishEntry("featured " + category, trio.toString());
    }
  }

  /** A facet file's counts by value. */
  private static Map<String, Integer> facet(String name) throws IOException {
    Map<String, Integer> counts = new HashMap<>();
    JsonObject root =
        JsonParser.parseString(read(FACETS_PATH + name + FACET_EXTENSION)).getAsJsonObject();
    for (JsonElement element : root.getAsJsonArray(FACET_VALUES)) {
      JsonObject value = element.getAsJsonObject();
      counts.put(value.get(FACET_VALUE).getAsString(), value.get(FACET_COUNT).getAsInt());
    }
    return counts;
  }

  /** The API counts each node's designs, children included: the category filter must agree. */
  private static void assertCounts(CatalogIndex index, List<CategoryNode> nodes) {
    for (CategoryNode node : nodes) {
      assertEquals(
          node.count(),
          index.filter(CatalogFilter.NONE.withCategory(node.path())).length,
          node.path());
      assertCounts(index, node.children());
    }
  }

  /** Every listed design has a file to download: a dropped format shows "No files" instead. */
  @Test
  void everyDesignHasAPrimaryFile(TestReporter reporter)
      throws IOException, CatalogFormatException {
    List<String> withoutFile = new ArrayList<>();
    for (SchematicSummary schematic : catalog.schematics()) {
      String path =
          RemotePaths.fill(manifest.schematicTemplate(), ApiJson.ID_PLACEHOLDER, schematic.id())
              .orElseThrow();
      if (ApiJson.detail(read(path)).primaryFile().isEmpty()) {
        withoutFile.add(schematic.id());
      }
    }
    reporter.publishEntry("designs checked", Integer.toString(catalog.schematics().size()));
    reporter.publishEntry("designs without a primary file", Integer.toString(withoutFile.size()));
    assertEquals(List.of(), withoutFile);
  }

  @Test
  void cacheRoundTripAndOpenTime(TestReporter reporter) throws IOException, CatalogFormatException {
    byte[] cache = writeCache(catalog);
    reporter.publishEntry("cache file KB", Integer.toString(cache.length / 1024));
    long[] write = new long[TIMED_ROUNDS];
    long[] readOnly = new long[TIMED_ROUNDS];
    long[] open = new long[TIMED_ROUNDS];
    long firstOpen = 0;
    Catalog read = null;
    for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
      long writeStart = System.nanoTime();
      writeCache(catalog);
      long start = System.nanoTime();
      read = readCache(cache);
      long readEnd = System.nanoTime();
      CatalogIndex.of(read.schematics(), read.locale());
      CategoryTree.rows(read);
      long elapsed = System.nanoTime() - start;
      if (round == 0) {
        firstOpen = elapsed;
      }
      if (round >= WARMUP_ROUNDS) {
        write[round - WARMUP_ROUNDS] = start - writeStart;
        readOnly[round - WARMUP_ROUNDS] = readEnd - start;
        open[round - WARMUP_ROUNDS] = elapsed;
      }
    }
    assertEquals(catalog, read);
    reporter.publishEntry("catalog retained MB (approx.)", retainedMegabytes(cache, false));
    reporter.publishEntry("catalog + index retained MB (approx.)", retainedMegabytes(cache, true));
    reporter.publishEntry("cache write ms (median)", millis(median(write)));
    reporter.publishEntry("cache read ms (median)", millis(median(readOnly)));
    reporter.publishEntry("cache read + index ms (first round, cold JIT)", millis(firstOpen));
    reporter.publishEntry("cache read + index ms (median)", millis(median(open)));
  }

  /**
   * Typing in the search box. The full pass over the catalog no longer runs on the render thread
   * (it is reported, not budgeted); typing on searches only the last matches ({@link
   * CatalogIndex#refine}); the render thread only hands the search over and takes the result.
   */
  @Test
  void searchFitsTheKeystrokeBudget(TestReporter reporter) throws InterruptedException {
    long start = System.nanoTime();
    CatalogIndex index = CatalogIndex.of(catalog.schematics(), catalog.locale());
    reporter.publishEntry("index build ms", millis(System.nanoTime() - start));
    String[] keystrokes = new String[TYPED.length()];
    for (int length = 1; length <= TYPED.length(); length++) {
      keystrokes[length - 1] = TYPED.substring(0, length);
    }
    long[] full = new long[TIMED_ROUNDS * keystrokes.length];
    long[] refined = new long[TIMED_ROUNDS * keystrokes.length];
    long[] renderThread = new long[TIMED_ROUNDS * keystrokes.length];
    BlockingQueue<Runnable> results = new LinkedBlockingQueue<>();
    CatalogSearch search = new CatalogSearch(results::add);
    int[] delivered = new int[1];
    try {
      int timed = 0;
      for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
        search.filterNow(index, CatalogFilter.NONE);
        for (String query : keystrokes) {
          CatalogFilter filter = CatalogFilter.NONE.withText(query);
          long fullStart = System.nanoTime();
          int[] expected = index.filter(filter);
          long fullElapsed = System.nanoTime() - fullStart;
          long refinedStart = System.nanoTime();
          int[] found = search.filterNow(index, filter);
          long refinedElapsed = System.nanoTime() - refinedStart;
          assertArrayEquals(expected, found, query);
          long requestStart = System.nanoTime();
          search.request(index, filter, matches -> delivered[0]++);
          long requestElapsed = System.nanoTime() - requestStart;
          Runnable result = results.poll(RESULT_WAIT_SECONDS, TimeUnit.SECONDS);
          assertNotNull(result, query);
          long resultStart = System.nanoTime();
          result.run();
          long resultElapsed = System.nanoTime() - resultStart;
          if (round >= WARMUP_ROUNDS) {
            full[timed] = fullElapsed;
            refined[timed] = refinedElapsed;
            renderThread[timed] = requestElapsed + resultElapsed;
            timed++;
          }
        }
      }
    } finally {
      search.close();
    }
    assertEquals((WARMUP_ROUNDS + TIMED_ROUNDS) * keystrokes.length, delivered[0]);
    reporter.publishEntry("full filter ms per keystroke (median)", millis(median(full)));
    reporter.publishEntry("full filter ms per keystroke (max)", millis(max(full)));
    reporter.publishEntry("refined filter ms per keystroke (median)", millis(median(refined)));
    reporter.publishEntry("refined filter ms per keystroke (max)", millis(max(refined)));
    reporter.publishEntry("render thread ms per keystroke (median)", millis(median(renderThread)));
    reporter.publishEntry("render thread ms per keystroke (max)", millis(max(renderThread)));
    double refinedMillis = median(refined) / NANOS_PER_MILLI;
    assertTrue(refinedMillis < MAX_REFINED_MILLIS, "median refined " + refinedMillis + " ms");
    double renderMillis = median(renderThread) / NANOS_PER_MILLI;
    assertTrue(
        renderMillis < MAX_RENDER_THREAD_MILLIS, "median render thread " + renderMillis + " ms");
  }

  /**
   * Heap kept by one read catalog, and its index when {@code withIndex}, from used heap after full
   * collections.
   */
  private static String retainedMegabytes(byte[] cache, boolean withIndex)
      throws IOException, CatalogFormatException {
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long before = runtime.totalMemory() - runtime.freeMemory();
    Catalog kept = readCache(cache);
    CatalogIndex index = withIndex ? CatalogIndex.of(kept.schematics(), kept.locale()) : null;
    System.gc();
    long after = runtime.totalMemory() - runtime.freeMemory();
    assertEquals(kept.schematics().size(), index == null ? kept.schematics().size() : index.size());
    return String.format(Locale.ROOT, "%.1f", (after - before) / BYTES_PER_MEGABYTE);
  }

  private static byte[] writeCache(Catalog written) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    CatalogCacheCodec.write(written, API, output);
    return output.toByteArray();
  }

  private static Catalog readCache(byte[] cache) throws IOException, CatalogFormatException {
    return CatalogCacheCodec.read(new ByteArrayInputStream(cache), API);
  }

  /** Heap kept by the locale's texts alone (titles, tag and category names). */
  @Test
  void localeTextsRetainedSize(TestReporter reporter) throws IOException, CatalogFormatException {
    String json = read(LOCALE_PATH);
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long before = runtime.totalMemory() - runtime.freeMemory();
    LocaleTexts kept = ApiJson.locale(LANGUAGE, json);
    System.gc();
    long after = runtime.totalMemory() - runtime.freeMemory();
    assertTrue(kept.titles().size() > 0);
    reporter.publishEntry("locale titles", Integer.toString(kept.titles().size()));
    reporter.publishEntry(
        "locale texts retained MB (approx.)",
        String.format(Locale.ROOT, "%.2f", (after - before) / BYTES_PER_MEGABYTE));
  }

  private static String read(String path) throws IOException {
    return Files.readString(API_FOLDER.resolve(path), StandardCharsets.UTF_8);
  }

  private static long max(long[] values) {
    return Arrays.stream(values).max().orElseThrow();
  }

  private static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI);
  }
}
