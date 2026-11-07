package com.einsteinmods.schematicvault.core.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.nbt.NbtReader;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtWriter;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.SpongeReader;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only, over the API's real design files, read from its local copy and never copied into the
 * repository; skipped where that folder does not exist (CI, the public repo). Rules on the middle
 * layers of the biggest file of each format and of a sample of each: rewrite time, and every cell
 * read back against what the rules describe. Nothing here ships.
 */
class RealLayerRewriteTest {
  private static final Path FILES = LocalApiFiles.apiFolder().resolve("v1/files");

  /**
   * Biggest file timed. Past it sits one 3.6 MB litematic with 970,000 block entities, 77 MB of NBT
   * inflated, whose two trees do not fit this 2 GB test heap together.
   */
  private static final long MAX_FILE_BYTES = 3L * 1024 * 1024;

  /** One file in this many; every file with {@code -Dschematicvault.allFiles=true}. */
  private static final int SAMPLE_STEP = 200;

  private static final boolean ALL_FILES = Boolean.getBoolean("schematicvault.allFiles");
  private static final int MAX_SAMPLE_FILE_BYTES = 256 * 1024;
  private static final int TIMED_ROUNDS = 3;
  private static final int LAYER_PARTS = 3;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
  private static final String TARGET = "minecraft:spruce_planks";
  private static final String REMOVED = "minecraft:air";

  private static final List<Path> PATHS = new ArrayList<>();
  private static LegacyBlocks legacy;

  @BeforeAll
  static void setUp() throws IOException {
    assumeTrue(Files.isDirectory(FILES), "no local API copy");
    legacy = LegacyBlocks.load();
    try (Stream<Path> files = Files.walk(FILES)) {
      files.filter(Files::isRegularFile).forEach(PATHS::add);
    }
  }

  private static SchematicFormat format(Path file) {
    String name = file.getFileName().toString();
    return SchematicFormat.fromExtension(name.substring(name.lastIndexOf('.') + 1)).orElse(null);
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  /** The two most common blocks: the first changes in the middle, the second goes on top. */
  private static LayerChanges changes(VoxelVolume volume) {
    Map<String, Long> byBlock =
        IntStream.range(1, volume.palette().size())
            .boxed()
            .collect(
                Collectors.groupingBy(
                    index -> BlockStateString.parse(volume.palette().get(index)).name(),
                    Collectors.summingLong(index -> volume.paletteCounts()[index])));
    List<String> common =
        byBlock.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .limit(2)
            .toList();
    int height = volume.sizeY();
    int firstThird = Math.min(height - 1, height / LAYER_PARTS);
    int secondThird = Math.min(height - 1, firstThird * 2);
    List<BlockRule> rules = new ArrayList<>();
    rules.add(new BlockRule(common.getFirst(), TARGET, new LayerRange(firstThird, secondThird)));
    if (common.size() > 1) {
      rules.add(new BlockRule(common.getLast(), REMOVED, new LayerRange(secondThird, height - 1)));
    }
    RuleBands bands = RuleBands.of(rules, height);
    return new LayerChanges(
        bands.bandOfY(),
        bands.count(),
        (band, state) -> {
          BlockRule rule = bands.rule(band, BlockStateString.parse(state).name());
          return rule == null ? null : new StateChange(rule.targetBlock(), false);
        });
  }

  /** The edited file's tree, as {@code EditedFiles} makes it. */
  private static CompoundTag edit(
      SchematicFormat format, CompoundTag root, VoxelVolume volume, LayerChanges changes)
      throws VolumeFormatException {
    if (format != SchematicFormat.SCHEMATIC || SpongeReader.isSponge(root)) {
      return SchematicRewriter.rewrite(format, root, changes);
    }
    List<List<String>> statesByBand = new ArrayList<>();
    for (int band = 0; band < changes.bands(); band++) {
      List<String> states = new ArrayList<>();
      for (String state : volume.palette()) {
        StateChange change =
            BlockStateString.isAir(state) ? null : changes.mapper().change(band, state);
        states.add(change == null ? state : change.state());
      }
      statesByBand.add(states);
    }
    return LitematicWriter.write(
        new LitematicWriter.Metadata("Real", "", "", 0, 0),
        LitematicWriter.Contents.ofBands(
            volume.sizeX(),
            volume.sizeY(),
            volume.sizeZ(),
            statesByBand,
            volume.cells(),
            changes.bandOfY(),
            Map.of()));
  }

  /** The file's volume; null when the preview could not show it (damaged, past its limits). */
  private static @Nullable VoxelVolume read(Path file, byte[] bytes) {
    try {
      return SchematicReaders.read(format(file), bytes, () -> legacy);
    } catch (VolumeFormatException exception) {
      return null;
    }
  }

  /**
   * Rewrites a file the preview can show and checks every cell.
   *
   * @return the best rewrite time, in nanoseconds; -1 when the preview would refuse the file
   */
  private static long check(Path file, int rounds)
      throws IOException, VolumeFormatException, NbtFormatException {
    SchematicFormat format = format(file);
    byte[] bytes = Files.readAllBytes(file);
    VoxelVolume volume = read(file, bytes);
    if (volume == null || volume.palette().size() < 2 || volume.sizeY() == 0) {
      return -1;
    }
    CompoundTag root = NbtReader.read(bytes);
    LayerChanges changes = changes(volume);
    long best = Long.MAX_VALUE;
    CompoundTag edited = root;
    for (int round = 0; round < rounds; round++) {
      long start = System.nanoTime();
      edited = edit(format, root, volume, changes);
      best = Math.min(best, System.nanoTime() - start);
    }
    SchematicFormat saved = SchematicRewriter.savedFormat(format, root);
    VoxelVolume back = SchematicReaders.read(saved, NbtWriter.gzip(edited), () -> legacy);
    LayerRewriteTest.assertCells(volume, back, changes);
    return best;
  }

  @Test
  void theBiggestFileOfEachFormatReadsBackCellByCell(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    List<Path> bySize = new ArrayList<>(PATHS);
    bySize.sort(Comparator.comparingLong(RealLayerRewriteTest::size).reversed());
    for (SchematicFormat format : SchematicFormat.values()) {
      for (Path file : bySize) {
        if (format(file) != format || size(file) > MAX_FILE_BYTES) {
          continue;
        }
        long best = check(file, TIMED_ROUNDS);
        if (best < 0) {
          continue;
        }
        reporter.publishEntry(
            format.id() + " biggest",
            String.format(
                Locale.ROOT,
                "%s, %.1f MB: rewrite %.1f ms",
                file.getFileName(),
                size(file) / BYTES_PER_MEGABYTE,
                best / NANOS_PER_MILLI));
        break;
      }
    }
  }

  @Test
  void aSampleOfTheFilesReadsBackCellByCell(TestReporter reporter)
      throws IOException, VolumeFormatException, NbtFormatException {
    int checked = 0;
    for (int index = 0; index < PATHS.size(); index += ALL_FILES ? 1 : SAMPLE_STEP) {
      Path file = PATHS.get(index);
      if (format(file) != null && size(file) <= MAX_SAMPLE_FILE_BYTES && check(file, 1) >= 0) {
        checked++;
      }
    }
    assertTrue(checked > 0, "no file sampled");
    reporter.publishEntry("sample", checked + " files checked cell by cell");
  }
}
