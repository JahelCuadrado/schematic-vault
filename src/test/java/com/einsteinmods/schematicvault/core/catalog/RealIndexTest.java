package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only checks and timings of the compact index against the pages of the same revision, read
 * from a local API build that has both; skipped where none exists (CI, the public repo). Timings
 * and memory are reported as test entries, never shipped.
 */
class RealIndexTest {
  private static final String INDEX_PART_ONE = "v1/schematics/index/1.json";

  private static final String TOTAL = "total";
  private static final int TIMED_ROUNDS = 5;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;

  private static Path folder;
  private static Manifest manifest;
  private static List<byte[]> parts;
  private static List<byte[]> pages;

  @BeforeAll
  static void readFiles() throws IOException, CatalogFormatException {
    folder = LocalApiFiles.apiFolder();
    assumeTrue(
        Files.isRegularFile(folder.resolve(INDEX_PART_ONE)), "no local API build with the index");
    manifest = ApiJson.manifest(Files.readString(folder.resolve("v1/manifest.json")));
    IndexEndpoint index = manifest.index();
    assertNotNull(index, "the manifest announces the index");
    parts = new ArrayList<>();
    for (int part = 1; part <= index.partCount(); part++) {
      parts.add(read(fill(index.template(), ApiJson.PART_PLACEHOLDER, part)));
    }
    pages = new ArrayList<>();
    for (int page = 1; page <= manifest.pageCount(); page++) {
      pages.add(read(fill(manifest.pageTemplate(), ApiJson.PAGE_PLACEHOLDER, page)));
    }
  }

  @Test
  void indexRowsAreThePagesDesignsInTheirOrder(TestReporter reporter)
      throws CatalogFormatException, IOException {
    List<SchematicSummary> fromIndex = parseIndex();
    List<SchematicSummary> fromPages = parsePages();
    int total = total(parts.getFirst());
    reporter.publishEntry("folder", folder.toString());
    reporter.publishEntry("index designs", Integer.toString(fromIndex.size()));
    assertEquals(total, fromIndex.size());
    assertEquals(fromPages.size(), fromIndex.size());
    int newestFromIndex = DesignScore.newestDataVersion(fromIndex);
    int newestFromPages = DesignScore.newestDataVersion(fromPages);
    assertEquals(newestFromPages, newestFromIndex);
    for (int design = 0; design < fromIndex.size(); design++) {
      SchematicSummary indexed = fromIndex.get(design);
      SchematicSummary paged = fromPages.get(design);
      assertEquals(IndexPartJsonTest.withoutIndexGaps(paged), indexed, paged.id());
      assertEquals(paged.primaryFileUrl(), indexed.primaryFileUrl(), paged.id());
      assertEquals(paged.thumbnailPngUrl(), indexed.thumbnailPngUrl(), paged.id());
      assertEquals(
          DesignScore.of(paged, newestFromPages),
          DesignScore.of(indexed, newestFromIndex),
          paged.id());
    }
  }

  /**
   * Parse time (bytes in memory, one thread) and heap: the peak while parsing over the heap before
   * it, and what the designs keep once parsed. The raw bytes, held by both paths, are not counted.
   */
  @Test
  void parseTimeAndMemory(TestReporter reporter) throws CatalogFormatException {
    long indexBytes = parts.stream().mapToLong(bytes -> bytes.length).sum();
    long pageBytes = pages.stream().mapToLong(bytes -> bytes.length).sum();
    reporter.publishEntry("index MB (4 parts, raw)", megabytes(indexBytes));
    reporter.publishEntry("pages MB (raw)", megabytes(pageBytes));
    long indexNanos = Long.MAX_VALUE;
    long pagesNanos = Long.MAX_VALUE;
    for (int round = 0; round < TIMED_ROUNDS; round++) {
      long start = System.nanoTime();
      parseIndex();
      indexNanos = Math.min(indexNanos, System.nanoTime() - start);
      start = System.nanoTime();
      parsePages();
      pagesNanos = Math.min(pagesNanos, System.nanoTime() - start);
    }
    reporter.publishEntry("index parse ms, 4 parts, best of 5", millis(indexNanos));
    reporter.publishEntry("pages parse ms, 603 pages, best of 5", millis(pagesNanos));
    long largestPartNanos = Long.MAX_VALUE;
    for (int round = 0; round < TIMED_ROUNDS; round++) {
      long start = System.nanoTime();
      IndexPartJson.read(parts.getFirst(), 1, parts.size(), manifest.shardRoots());
      largestPartNanos = Math.min(largestPartNanos, System.nanoTime() - start);
    }
    reporter.publishEntry("index parse ms, part 1 alone", millis(largestPartNanos));
    Heap index = heapWhile(RealIndexTest::parseIndex);
    Heap paged = heapWhile(RealIndexTest::parsePages);
    reporter.publishEntry("index parse heap peak MB over baseline", megabytes(index.peak()));
    reporter.publishEntry("pages parse heap peak MB over baseline", megabytes(paged.peak()));
    reporter.publishEntry("index designs retained MB", megabytes(index.retained()));
    reporter.publishEntry("pages designs retained MB", megabytes(paged.retained()));
  }

  /** The part's {@code total}, read as a stream (a tree of the part would skew the heap). */
  private static int total(byte[] part) throws IOException {
    try (JsonReader reader =
        new JsonReader(
            new InputStreamReader(new ByteArrayInputStream(part), StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {
        if (reader.nextName().equals(TOTAL)) {
          return reader.nextInt();
        }
        reader.skipValue();
      }
    }
    throw new IOException("index part without a total");
  }

  private static List<SchematicSummary> parseIndex() throws CatalogFormatException {
    List<SchematicSummary> all = new ArrayList<>();
    for (int part = 1; part <= parts.size(); part++) {
      all.addAll(
          IndexPartJson.read(parts.get(part - 1), part, parts.size(), manifest.shardRoots()));
    }
    return all;
  }

  private static List<SchematicSummary> parsePages() throws CatalogFormatException {
    List<SchematicSummary> all = new ArrayList<>();
    for (byte[] page : pages) {
      all.addAll(ApiJson.page(new String(page, StandardCharsets.UTF_8)));
    }
    return all;
  }

  /** Heap peak during {@code parse} and retained after it, both over the heap before it. */
  private static Heap heapWhile(Parse parse) throws CatalogFormatException {
    List<MemoryPoolMXBean> heapPools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .toList();
    long before = settledHeap();
    heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
    List<SchematicSummary> designs = parse.run();
    long peak = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    long retained = settledHeap();
    assertFalse(designs.isEmpty());
    return new Heap(peak - before, retained - before);
  }

  private static long settledHeap() {
    Runtime runtime = Runtime.getRuntime();
    for (int collection = 0; collection < 3; collection++) {
      System.gc();
    }
    return runtime.totalMemory() - runtime.freeMemory();
  }

  @FunctionalInterface
  private interface Parse {
    List<SchematicSummary> run() throws CatalogFormatException;
  }

  private record Heap(long peak, long retained) {}

  private static String fill(String template, String placeholder, int number) {
    return RemotePaths.fill(template, placeholder, Integer.toString(number)).orElseThrow();
  }

  private static byte[] read(String path) throws IOException {
    return Files.readAllBytes(folder.resolve(path));
  }

  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MILLI);
  }

  private static String megabytes(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MEGABYTE);
  }
}
