package com.einsteinmods.schematicvault.timing;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only: what the preview's palette step costs in the game's own data fixer, on real designs of
 * the API's local copy (skipped where it does not exist). The client optimizes the level types at
 * startup ({@code Main} calls {@code DataFixers.optimize}); this test does the same first and
 * reports that separately. Also counts the palette entries the game's registry does not know, with
 * and without the fixer. Nothing here ships.
 */
class DataFixerTimingTest {
  private static final Path FILES = LocalApiFiles.apiFolder().resolve("v1/files");

  /** The catalog's median design (an old 1.13 MCEdit file) and two recent litematics. */
  private static final String[][] DESIGNS = {
    {"94/94de7b8c2844d952f0b977452916becdba4d39f96378a37fcff3d248989cee9e.schematic", "schematic"},
    {"ed/ed7504187d66267771631dffa2ba124c51a3ba10dc09ad362c08b491237c20e4.litematic", "litematic"},
    {"bf/bf52a3f185ede546516a797f788ea35e74caf8e7b04c95554d4f135b27532ad4.litematic", "litematic"},
  };

  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static long optimizeNanos;

  @BeforeAll
  static void bootstrap() {
    assumeTrue(Files.isDirectory(FILES), "no local API copy");
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    long start = System.nanoTime();
    DataFixers.optimize(DataFixTypes.TYPES_FOR_LEVEL_LIST).join();
    optimizeNanos = System.nanoTime() - start;
  }

  @Test
  void timesThePaletteFix(TestReporter reporter) throws IOException, VolumeFormatException {
    reporter.publishEntry("optimize ms (client startup)", fmt(optimizeNanos));
    DataFixer fixer = DataFixers.getDataFixer();
    int current = SharedConstants.getCurrentVersion().dataVersion().version();
    LegacyBlocks legacy = LegacyBlocks.load();
    for (String[] design : DESIGNS) {
      byte[] file =
          Files.readAllBytes(LocalApiFiles.file(FILES.getParent().getParent(), design[0]));
      VoxelVolume volume =
          SchematicReaders.read(
              SchematicFormat.fromExtension(design[1]).orElseThrow(), file, () -> legacy);
      long first = 0;
      long total = 0;
      int unknownBefore = 0;
      int unknownAfter = 0;
      Set<String> unknownNames = new TreeSet<>();
      for (int round = 0; round < 2; round++) {
        long start = System.nanoTime();
        unknownAfter = 0;
        unknownBefore = 0;
        for (int index = 1; index < volume.palette().size(); index++) {
          BlockStateString.Parsed parsed = BlockStateString.parse(volume.palette().get(index));
          unknownBefore += known(parsed.name()) ? 0 : 1;
          String fixed = fixedName(fixer, parsed, volume.dataVersion(), current);
          if (!known(fixed)) {
            unknownAfter++;
            unknownNames.add(fixed);
          }
        }
        long elapsed = System.nanoTime() - start;
        if (round == 0) {
          first = elapsed;
        } else {
          total = elapsed;
        }
      }
      reporter.publishEntry(
          design[0].substring(3, 11),
          String.format(
              Locale.ROOT,
              "data version %d, %d palette entries: first pass %.1f ms, warm %.2f ms;"
                  + " unknown names %d before the fix, %d after %s",
              volume.dataVersion(),
              volume.palette().size() - 1,
              first / NANOS_PER_MILLI,
              total / NANOS_PER_MILLI,
              unknownBefore,
              unknownAfter,
              unknownNames));
    }
  }

  private static boolean known(String name) {
    Identifier id = Identifier.tryParse(name);
    return id != null && BuiltInRegistries.BLOCK.getOptional(id).isPresent();
  }

  private static String fixedName(
      DataFixer fixer, BlockStateString.Parsed parsed, int from, int to) {
    if (from <= 0 || from >= to) {
      return parsed.name();
    }
    // Block states are {Name, Properties} before data version 5006, {id, properties} from it.
    boolean legacyFields = from < 5006;
    CompoundTag properties = new CompoundTag();
    parsed.properties().forEach(properties::putString);
    CompoundTag state = new CompoundTag();
    state.putString(legacyFields ? "Name" : "id", parsed.name());
    state.put(legacyFields ? "Properties" : "properties", properties);
    Tag result =
        fixer
            .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, state), from, to)
            .getValue();
    return result instanceof CompoundTag fixed
        ? fixed.getStringOr("id", parsed.name())
        : parsed.name();
  }

  private static String fmt(long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MILLI);
  }
}
