package com.einsteinmods.schematicvault.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.edit.BlockShapes;
import com.einsteinmods.schematicvault.core.edit.BlockTally;
import com.einsteinmods.schematicvault.core.edit.RuleBands;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VisibleBlocks;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only, run on demand ({@code ./gradlew test -Dschematicvault.editorTiming=true --tests
 * '*EditorTimingTest'}; skipped too where the API's local copy does not exist): what the block
 * editor costs on real designs: the palette remap after a change (render thread), the visibility
 * pass the rebuild repeats, whole and cut at half height for the layer slider (worker; the
 * tesselation itself needs the game's models and is measured in game), the edited file's rewrite,
 * and the picker's candidate list. Also checks each rewritten file reads back with the same block
 * count. Nothing here ships.
 */
class EditorTimingTest {
  private static final Path API_FOLDER = LocalApiFiles.apiFolder();

  private static final String PAGES = "v1/schematics/pages";

  /** Off by default: it loads the biggest designs, past what the build's shared test heap holds. */
  private static final String ENABLED = "schematicvault.editorTiming";

  private static final long[] TIMED_BLOCK_COUNTS = {2_400, 146_000, 500_000};
  private static final int REPLACED_TYPES = 3;
  private static final int WARMUP_ROUNDS = 20;
  private static final int TIMED_ROUNDS = 50;
  private static final int FILE_ROUNDS = 3;
  private static final double NANOS_PER_MICRO = 1_000.0;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
  private static final String FALLBACK_TARGET = "minecraft:stone";

  /**
   * Biggest file timed per format. Past it sits one 10.5 MB structure that inflates to 132 MB, a
   * tree of 3.6 M block compounds that does not fit this 2 GB test heap even to be read.
   */
  private static final long MAX_TIMED_FILE_BYTES = 4L * 1024 * 1024;

  private record Design(SchematicFormat format, Path file, long blockCount, long bytes) {}

  private static final List<Design> DESIGNS = new ArrayList<>();
  private static LegacyBlocks legacy;

  @BeforeAll
  static void setUp() throws IOException {
    assumeTrue(Boolean.getBoolean(ENABLED), "run with -D" + ENABLED + "=true");
    Path pages = API_FOLDER.resolve(PAGES);
    assumeTrue(Files.isDirectory(pages), "no local API copy");
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    DataFixers.optimize(DataFixTypes.TYPES_FOR_LEVEL_LIST).join();
    legacy = LegacyBlocks.load();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(pages, "*.json")) {
      for (Path page : files) {
        JsonObject json = JsonParser.parseString(Files.readString(page)).getAsJsonObject();
        for (JsonElement element : json.getAsJsonArray("items")) {
          JsonObject item = element.getAsJsonObject();
          String url = item.get("primaryFileUrl").getAsString();
          SchematicFormat format =
              SchematicFormat.fromExtension(url.substring(url.lastIndexOf('.') + 1)).orElse(null);
          Path file = LocalApiFiles.of(API_FOLDER, url);
          if (format != null && Files.isRegularFile(file)) {
            DESIGNS.add(
                new Design(format, file, item.get("blockCount").getAsLong(), Files.size(file)));
          }
        }
      }
    }
  }

  /** The most common types replaced: same shape when another block has it, else stone. */
  private static BlockReplacements replacements(BlockTally tally) {
    BlockReplacements replacements = BlockReplacements.NONE;
    for (BlockTally.Entry entry :
        tally.entries().subList(0, Math.min(REPLACED_TYPES, tally.entries().size()))) {
      String target = FALLBACK_TARGET;
      for (Block block : BuiltInRegistries.BLOCK) {
        String id = BlockEdits.id(block);
        if (!id.equals(entry.blockId()) && BlockShapes.sameShape(entry.blockId(), id)) {
          target = id;
          break;
        }
      }
      replacements = replacements.with(new BlockRule(entry.blockId(), target, LayerRange.ALL));
    }
    return replacements;
  }

  private static LoadedFile load(Design design)
      throws IOException, VolumeFormatException, NbtFormatException {
    byte[] bytes = Files.readAllBytes(design.file());
    VoxelVolume volume = SchematicReaders.read(design.format(), bytes, () -> legacy);
    return LoadedFile.of(volume, bytes, design.format());
  }

  @Test
  void timesTheRemapTheRebuildPassAndTheRewrite(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    for (long target : TIMED_BLOCK_COUNTS) {
      Design design =
          DESIGNS.stream()
              .min(Comparator.comparingLong(candidate -> Math.abs(candidate.blockCount() - target)))
              .orElseThrow();
      LoadedFile file = load(design);
      BlockReplacements replacements = replacements(file.tally());
      long remap = Long.MAX_VALUE;
      long visibility = Long.MAX_VALUE;
      long cutVisibility = Long.MAX_VALUE;
      int halfHeight = Math.max(1, file.volume().sizeY() / 2);
      LayerRange lowerHalf = new LayerRange(0, halfHeight - 1);
      LoadedFile.EditedPalette edited = null;
      for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
        long start = System.nanoTime();
        edited = file.edited(replacements);
        long remapped = System.nanoTime();
        VisibleBlocks.of(file.volume(), edited.opaqueByBand(), edited.bandOfY(), LayerRange.ALL);
        long done = System.nanoTime();
        VisibleBlocks.of(file.volume(), edited.opaqueByBand(), edited.bandOfY(), lowerHalf);
        long cutDone = System.nanoTime();
        if (round >= WARMUP_ROUNDS) {
          remap = Math.min(remap, remapped - start);
          visibility = Math.min(visibility, done - remapped);
          cutVisibility = Math.min(cutVisibility, cutDone - done);
        }
      }
      long rewrite = rewrite(file, edited, design);
      reporter.publishEntry(
          "~" + target + " blocks",
          String.format(
              Locale.ROOT,
              "%s %s, %d blocks, %d layers, %d palette entries, %d types, replaced %s: remap"
                  + " %.1f µs, visibility %.2f ms, cut at layer %d %.2f ms, edited file %.1f ms",
              design.format().id(),
              design.file().getFileName(),
              file.volume().blockCount(),
              file.volume().sizeY(),
              file.volume().palette().size(),
              file.tally().entries().size(),
              replacements.rules(),
              remap / NANOS_PER_MICRO,
              visibility / NANOS_PER_MILLI,
              halfHeight,
              cutVisibility / NANOS_PER_MILLI,
              rewrite / NANOS_PER_MILLI));
    }
  }

  /** The same replacements on two ranges: what a change costs once rules split the layers. */
  private static BlockReplacements rangeReplacements(BlockReplacements whole, int height) {
    int middle = Math.max(1, height / 2);
    BlockReplacements ranged = BlockReplacements.NONE;
    int rule = 0;
    for (BlockRule chosen : whole.rules()) {
      LayerRange layers =
          rule++ % 2 == 0
              ? new LayerRange(0, middle - 1)
              : new LayerRange(middle / 2, Math.max(middle / 2, height - 1));
      ranged = ranged.with(new BlockRule(chosen.sourceBlock(), chosen.targetBlock(), layers));
    }
    return ranged;
  }

  /** Rules limited to some layers: bands, remapped palettes, visibility, list and saved file. */
  @Test
  void timesARangeChange(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    for (long target : TIMED_BLOCK_COUNTS) {
      Design design =
          DESIGNS.stream()
              .min(Comparator.comparingLong(candidate -> Math.abs(candidate.blockCount() - target)))
              .orElseThrow();
      LoadedFile file = load(design);
      BlockReplacements ranged =
          rangeReplacements(replacements(file.tally()), file.volume().sizeY());
      long bands = Long.MAX_VALUE;
      long remap = Long.MAX_VALUE;
      long visibility = Long.MAX_VALUE;
      long list = Long.MAX_VALUE;
      LoadedFile.EditedPalette edited = null;
      for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
        long start = System.nanoTime();
        RuleBands.of(ranged.rules(), file.volume().sizeY());
        long banded = System.nanoTime();
        edited = file.edited(ranged);
        long remapped = System.nanoTime();
        VisibleBlocks.of(file.volume(), edited.opaqueByBand(), edited.bandOfY(), LayerRange.ALL);
        long visible = System.nanoTime();
        file.blockList(edited);
        long listed = System.nanoTime();
        if (round >= WARMUP_ROUNDS) {
          bands = Math.min(bands, banded - start);
          remap = Math.min(remap, remapped - banded);
          visibility = Math.min(visibility, visible - remapped);
          list = Math.min(list, listed - visible);
        }
      }
      long best = Long.MAX_VALUE;
      for (int round = 0; round < FILE_ROUNDS; round++) {
        long start = System.nanoTime();
        EditedFiles.write(file, edited, design.format(), "Timing", "Test", 0);
        best = Math.min(best, System.nanoTime() - start);
      }
      reporter.publishEntry(
          "~" + target + " blocks, ranges",
          String.format(
              Locale.ROOT,
              "%s %s, %d cells, %d palette entries, %d rules, %d bands: bands %.1f µs, remap %.1f"
                  + " µs, visibility %.2f ms, whole list %.2f ms, edited file %.1f ms",
              design.format().id(),
              design.file().getFileName(),
              file.volume().volume(),
              file.volume().palette().size(),
              ranged.rules().size(),
              edited.bands().count(),
              bands / NANOS_PER_MICRO,
              remap / NANOS_PER_MICRO,
              visibility / NANOS_PER_MILLI,
              list / NANOS_PER_MILLI,
              best / NANOS_PER_MILLI));
    }
  }

  /** The material list of half the layers: what the live list costs per layer crossed. */
  @Test
  void timesTheTallyOfARange(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    for (long target : TIMED_BLOCK_COUNTS) {
      Design design =
          DESIGNS.stream()
              .min(Comparator.comparingLong(candidate -> Math.abs(candidate.blockCount() - target)))
              .orElseThrow();
      LoadedFile file = load(design);
      LayerRange lowerHalf = new LayerRange(0, Math.max(1, file.volume().sizeY() / 2) - 1);
      LayerRange most = new LayerRange(0, Math.max(0, file.volume().sizeY() - 2));
      long half = Long.MAX_VALUE;
      long nearlyAll = Long.MAX_VALUE;
      long list = Long.MAX_VALUE;
      LoadedFile.EditedPalette edited = file.edited(BlockReplacements.NONE);
      for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
        long start = System.nanoTime();
        BlockTally.of(file.palette().blockIds(), file.volume(), lowerHalf);
        long halfDone = System.nanoTime();
        BlockTally.of(file.palette().blockIds(), file.volume(), most);
        long mostDone = System.nanoTime();
        file.blockList(edited, most);
        long listDone = System.nanoTime();
        if (round >= WARMUP_ROUNDS) {
          half = Math.min(half, halfDone - start);
          nearlyAll = Math.min(nearlyAll, mostDone - halfDone);
          list = Math.min(list, listDone - mostDone);
        }
      }
      reporter.publishEntry(
          "~" + target + " blocks",
          String.format(
              Locale.ROOT,
              "%s, %d blocks, %d cells, %d layers, %d palette entries, %d types: tally of half"
                  + " %.3f ms, of all but one layer %.3f ms, its whole list %.3f ms",
              design.file().getFileName(),
              file.volume().blockCount(),
              file.volume().volume(),
              file.volume().sizeY(),
              file.volume().palette().size(),
              file.tally().entries().size(),
              half / NANOS_PER_MILLI,
              nearlyAll / NANOS_PER_MILLI,
              list / NANOS_PER_MILLI));
    }
  }

  @Test
  void timesTheRewriteOfTheBiggestFileOfEachFormat(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    List<Design> bySize = new ArrayList<>(DESIGNS);
    bySize.sort(Comparator.comparingLong(Design::bytes).reversed());
    for (SchematicFormat format : SchematicFormat.values()) {
      for (Design design : bySize) {
        if (design.format() != format || design.bytes() > MAX_TIMED_FILE_BYTES) {
          continue;
        }
        LoadedFile file;
        try {
          file = load(design);
        } catch (VolumeFormatException exception) {
          // Past the preview's box limit: the editor never sees it.
          continue;
        }
        LoadedFile.EditedPalette edited = file.edited(replacements(file.tally()));
        long rewrite = rewrite(file, edited, design);
        reporter.publishEntry(
            format.id() + " biggest",
            String.format(
                Locale.ROOT,
                "%s, %.1f MB, %d blocks: edited file %.1f ms",
                design.file().getFileName(),
                design.bytes() / BYTES_PER_MEGABYTE,
                file.volume().blockCount(),
                rewrite / NANOS_PER_MILLI));
        break;
      }
    }
  }

  /** Best time of a few rewrites; the result reads back with the same block count. */
  private static long rewrite(LoadedFile file, LoadedFile.EditedPalette edited, Design design)
      throws VolumeFormatException, NbtFormatException {
    long best = Long.MAX_VALUE;
    EditedFile written = null;
    for (int round = 0; round < FILE_ROUNDS; round++) {
      long start = System.nanoTime();
      written = EditedFiles.write(file, edited, design.format(), "Timing", "Test", 0);
      best = Math.min(best, System.nanoTime() - start);
    }
    VoxelVolume back = SchematicReaders.read(written.format(), written.bytes(), () -> legacy);
    long nonAir = 0;
    for (int index = 1; index < back.palette().size(); index++) {
      nonAir += back.paletteCounts()[index];
    }
    long before = 0;
    for (int index = 1; index < file.volume().palette().size(); index++) {
      before += file.volume().paletteCounts()[index];
    }
    assertEquals(before, nonAir, design.file().getFileName() + " lost blocks");
    return best;
  }

  @Test
  void timesThePickerList(TestReporter reporter) {
    long best = Long.MAX_VALUE;
    int count = 0;
    for (int round = 0; round < WARMUP_ROUNDS; round++) {
      long start = System.nanoTime();
      List<String> names = new ArrayList<>();
      for (Block block : BuiltInRegistries.BLOCK) {
        if (block.asItem() != Items.AIR) {
          String name = block.getName().getString();
          // The icons' ItemStacks need the game's bound components: not built here.
          names.add((name + " " + BlockEdits.id(block)).toLowerCase(Locale.ROOT));
        }
      }
      String[] sorted = names.toArray(String[]::new);
      Arrays.sort(sorted);
      count = sorted.length;
      best = Math.min(best, System.nanoTime() - start);
    }
    reporter.publishEntry(
        "picker",
        String.format(
            Locale.ROOT, "%d blocks offered: built in %.2f ms", count, best / NANOS_PER_MILLI));
  }
}
