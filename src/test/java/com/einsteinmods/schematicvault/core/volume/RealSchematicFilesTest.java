package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only checks and timings over the API's real design files, read from its local copy and never
 * copied into the repository (almost every design is "all rights reserved"); skipped where that
 * folder does not exist (CI, the public repo). Timings are reported as test entries, never shipped.
 */
class RealSchematicFilesTest {
  private static final Path API_FOLDER = LocalApiFiles.apiFolder();

  private static final String PAGES = "v1/schematics/pages";

  /**
   * Every file is checked with {@code -Dschematicvault.allFiles=true} (about 40 s); by default one
   * in {@link #SAMPLE_STEP} of each format.
   */
  private static final boolean ALL_FILES = Boolean.getBoolean("schematicvault.allFiles");

  private static final int SAMPLE_STEP = 10;
  private static final int WARMUP_ROUNDS = 3;
  private static final int TIMED_ROUNDS = 5;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;

  /** Bytes of one quad in the game's block vertex format: 4 vertices of 28 bytes. */
  private static final int QUAD_BYTES = 4 * 28;

  /** Designs whose block counts are timed: the catalog's median, p90 and the default limit. */
  private static final long[] TIMED_BLOCK_COUNTS = {2_400, 146_000, 500_000};

  private static final List<Design> DESIGNS = new ArrayList<>();
  private static LegacyBlocks legacy;

  private record Design(SchematicFormat format, Path file, long blockCount) {}

  @BeforeAll
  static void readPages() throws IOException {
    Path pages = API_FOLDER.resolve(PAGES);
    assumeTrue(Files.isDirectory(pages), "no local API copy");
    legacy = LegacyBlocks.load();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(pages, "*.json")) {
      for (Path page : files) {
        JsonObject json = JsonParser.parseString(Files.readString(page)).getAsJsonObject();
        for (JsonElement element : json.getAsJsonArray("items")) {
          JsonObject item = element.getAsJsonObject();
          String url = item.get("primaryFileUrl").getAsString();
          String extension = url.substring(url.lastIndexOf('.') + 1);
          SchematicFormat format = SchematicFormat.fromExtension(extension).orElse(null);
          Path file = LocalApiFiles.of(API_FOLDER, url);
          if (format != null && Files.isRegularFile(file)) {
            DESIGNS.add(new Design(format, file, item.get("blockCount").getAsLong()));
          }
        }
      }
    }
  }

  private VoxelVolume read(Design design) throws IOException, VolumeFormatException {
    return SchematicReaders.read(design.format(), Files.readAllBytes(design.file()), () -> legacy);
  }

  @Test
  void blockCountsMatchTheApiForEveryFormat(TestReporter reporter) throws IOException {
    Map<SchematicFormat, int[]> results = new EnumMap<>(SchematicFormat.class);
    List<String> mismatches = new ArrayList<>();
    Map<SchematicFormat, Integer> seen = new EnumMap<>(SchematicFormat.class);
    for (Design design : DESIGNS) {
      int position = seen.merge(design.format(), 1, Integer::sum);
      if (!ALL_FILES && position % SAMPLE_STEP != 1) {
        continue;
      }
      // Index 0: matching, 1: different, 2: refused as too large, 3: unreadable.
      int[] counts = results.computeIfAbsent(design.format(), format -> new int[4]);
      try {
        VoxelVolume volume = read(design);
        if (volume.blockCount() == design.blockCount()) {
          counts[0]++;
        } else {
          counts[1]++;
          if (mismatches.size() < 10) {
            mismatches.add(
                design.file().getFileName()
                    + " "
                    + volume.blockCount()
                    + " vs "
                    + design.blockCount());
          }
        }
      } catch (VolumeFormatException exception) {
        counts[exception.tooLarge() ? 2 : 3]++;
        if (!exception.tooLarge() && mismatches.size() < 10) {
          mismatches.add(design.file().getFileName() + " " + exception.getMessage());
        }
      }
    }
    for (Map.Entry<SchematicFormat, int[]> entry : results.entrySet()) {
      int[] counts = entry.getValue();
      reporter.publishEntry(
          entry.getKey().id(),
          String.format(
              Locale.ROOT,
              "match %d, differ %d, too large %d, unreadable %d",
              counts[0],
              counts[1],
              counts[2],
              counts[3]));
    }
    if (!mismatches.isEmpty()) {
      reporter.publishEntry("first mismatches", String.join("; ", mismatches));
    }
    for (SchematicFormat format : SchematicFormat.values()) {
      int[] counts = results.get(format);
      assertTrue(counts != null && counts[0] > 0, "no " + format.id() + " file matched the API");
      assertEquals(0, counts[1], format.id() + " files with a different block count");
    }
  }

  /** Designs tried per target before giving up. */
  private static final int MAX_CANDIDATES = 10;

  @Test
  void timesParsingAndVisibility(TestReporter reporter) throws IOException, VolumeFormatException {
    for (long target : TIMED_BLOCK_COUNTS) {
      // The nearest design whose file the readers accept (some exceed their decompressed limit).
      List<Design> nearest =
          DESIGNS.stream()
              .sorted(
                  Comparator.comparingLong(candidate -> Math.abs(candidate.blockCount() - target)))
              .limit(MAX_CANDIDATES)
              .toList();
      Design design = null;
      for (Design candidate : nearest) {
        try {
          SchematicReaders.read(
              candidate.format(), Files.readAllBytes(candidate.file()), () -> legacy);
          design = candidate;
          break;
        } catch (VolumeFormatException tooLarge) {
          reporter.publishEntry("skipped " + candidate.file().getFileName(), tooLarge.getMessage());
        }
      }
      assertTrue(design != null, "no readable design near " + target + " blocks");
      byte[] file = Files.readAllBytes(design.file());
      VoxelVolume volume = null;
      long parseNanos = Long.MAX_VALUE;
      long visibleNanos = Long.MAX_VALUE;
      int[] visible = new int[0];
      for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
        long start = System.nanoTime();
        volume = SchematicReaders.read(design.format(), file, () -> legacy);
        long parsed = System.nanoTime();
        boolean[] opaque = new boolean[volume.palette().size()];
        java.util.Arrays.fill(opaque, 1, opaque.length, true);
        visible = VisibleBlocks.of(volume, opaque);
        long done = System.nanoTime();
        if (round >= WARMUP_ROUNDS) {
          parseNanos = Math.min(parseNanos, parsed - start);
          visibleNanos = Math.min(visibleNanos, done - parsed);
        }
      }
      long exposedFaces = exposedFaces(volume, visible);
      reporter.publishEntry(
          "~" + target + " blocks",
          String.format(
              Locale.ROOT,
              "%s %s, %d blocks, box %dx%dx%d, file %.1f KB: parse %.1f ms, visibility %.2f ms,"
                  + " visible %d (%.0f %%), cells %.1f MB, exposed faces %d (mesh >= %.1f MB)",
              design.format().id(),
              design.file().getFileName(),
              volume.blockCount(),
              volume.sizeX(),
              volume.sizeY(),
              volume.sizeZ(),
              file.length / 1024.0,
              parseNanos / NANOS_PER_MILLI,
              visibleNanos / NANOS_PER_MILLI,
              visible.length,
              100.0 * visible.length / Math.max(1, volume.blockCount()),
              volume.volume() * (double) Integer.BYTES / BYTES_PER_MEGABYTE,
              exposedFaces,
              exposedFaces * (double) QUAD_BYTES / BYTES_PER_MEGABYTE));
    }
  }

  /** Faces of visible blocks that touch air or the box's border: a lower bound of the mesh. */
  private static long exposedFaces(VoxelVolume volume, int[] visible) {
    int[] cells = volume.cells();
    int sizeX = volume.sizeX();
    int sizeZ = volume.sizeZ();
    int layer = sizeX * sizeZ;
    long faces = 0;
    for (int index : visible) {
      int x = index % sizeX;
      int z = index / sizeX % sizeZ;
      int y = index / layer;
      faces += x == 0 || cells[index - 1] == VoxelVolume.AIR ? 1 : 0;
      faces += x == sizeX - 1 || cells[index + 1] == VoxelVolume.AIR ? 1 : 0;
      faces += z == 0 || cells[index - sizeX] == VoxelVolume.AIR ? 1 : 0;
      faces += z == sizeZ - 1 || cells[index + sizeX] == VoxelVolume.AIR ? 1 : 0;
      faces += y == 0 || cells[index - layer] == VoxelVolume.AIR ? 1 : 0;
      faces += y == volume.sizeY() - 1 || cells[index + layer] == VoxelVolume.AIR ? 1 : 0;
    }
    return faces;
  }
}
