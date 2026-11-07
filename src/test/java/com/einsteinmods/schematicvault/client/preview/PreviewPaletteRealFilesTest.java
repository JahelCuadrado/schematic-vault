package com.einsteinmods.schematicvault.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.volume.LegacyBlocks;
import com.einsteinmods.schematicvault.core.volume.SchematicReaders;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.einsteinmods.schematicvault.testing.LocalApiFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Dev-only: unknown blocks the preview's palette leaves in old real designs of the API's local copy
 * (skipped where it does not exist), and the stand-ins drawn for blocks of mods in every design
 * ({@code -Dschematicvault.allFiles=true}). Nothing here ships.
 */
class PreviewPaletteRealFilesTest {
  private static final Path FILES = LocalApiFiles.apiFolder().resolve("v1/files");

  private static final boolean ALL_FILES = Boolean.getBoolean("schematicvault.allFiles");

  private static final String VANILLA_NAMESPACE = "minecraft";

  /** A 1.19.2 litematic with 54 {@code minecraft:chain} (now {@code iron_chain}). */
  private static final String CHAIN_HOUSE =
      "9f/9fb49ee440320b6f5084343421a4b0c7088701d77089e7f6175e06d843c04700.litematic";

  /** Old designs (data versions 1968-4189) of each format. */
  private static final Object[][] SAMPLE = {
    {CHAIN_HOUSE, SchematicFormat.LITEMATIC},
    {
      "3d/3d533d233b7ef8cfeb6b79953797901fd0909c329bff3861d2802428d89f064c.litematic",
      SchematicFormat.LITEMATIC
    },
    {
      "97/9724472027d716bdc174ed69333626868be22b7b5afec5eb8a19da326e5ecd3c.litematic",
      SchematicFormat.LITEMATIC
    },
    {
      "0d/0d8754d24a561131baf8c5c84984f5dc1e7728935836f4a49e58ff06c07eeaf6.litematic",
      SchematicFormat.LITEMATIC
    },
    {
      "68/688af84c5eac0874c0115ee0d4afa47b1d28d81b28b0af3465d8f6cdb4a79bd5.nbt", SchematicFormat.NBT
    },
    {
      "83/838b945f5d10853db68dcc32071ead580c94b10e59e8671057a592b87954251a.nbt", SchematicFormat.NBT
    },
    {
      "32/32c4c9fa512f14124a38ae873984f7283d114a819b7a5f084d3387248d0c8d3f.schem",
      SchematicFormat.SCHEM
    },
    {
      "66/662b31c14145431c6d61cb4d34e6e6da1afd238e25613c764c217b407009c050.schem",
      SchematicFormat.SCHEM
    },
    {
      "7f/7fcfc118a721bf37e9ce99438f28d6f7b600c048125d171c8bd75c8bf8fa8a70.schem",
      SchematicFormat.SCHEM
    },
  };

  @BeforeAll
  static void bootstrap() {
    assumeTrue(Files.isDirectory(FILES), "no local API copy");
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void oldDesignsKeepTheirRenamedBlocks(TestReporter reporter)
      throws IOException, VolumeFormatException {
    LegacyBlocks legacy = LegacyBlocks.load();
    long total = 0;
    long chainHouseUnknown = -1;
    for (Object[] design : SAMPLE) {
      String file = (String) design[0];
      Path local = LocalApiFiles.file(FILES.getParent().getParent(), file);
      if (Files.notExists(local)) {
        // The API dropped this design since the sample was taken.
        reporter.publishEntry(file.substring(3, 11), "no longer in the API");
        continue;
      }
      VoxelVolume volume =
          SchematicReaders.read(
              (SchematicFormat) design[1], Files.readAllBytes(local), () -> legacy);
      long unknown = PreviewPalette.resolve(volume).standInBlocks();
      total += unknown;
      reporter.publishEntry(
          file.substring(3, 11), "data version " + volume.dataVersion() + ", unknown " + unknown);
      if (file.equals(CHAIN_HOUSE)) {
        chainHouseUnknown = unknown;
      }
    }
    reporter.publishEntry("unknown blocks in the sample", Long.toString(total));
    if (chainHouseUnknown >= 0) {
      assertEquals(0, chainHouseUnknown, "chains of the 1.19.2 house");
    }
  }

  @Test
  void vanillaBlocksAreNeverStandIns(TestReporter reporter) throws IOException {
    assumeTrue(ALL_FILES, "every design only with -Dschematicvault.allFiles=true");
    LegacyBlocks legacy = LegacyBlocks.load();
    long total = 0;
    // Data version and state as the file has them -> cells drawn as a stand-in.
    Map<String, Long> left = new TreeMap<>();
    for (Path path : designFiles()) {
      String name = path.getFileName().toString();
      SchematicFormat format = formatOf(name);
      if (format == null) {
        continue;
      }
      VoxelVolume volume;
      PreviewPalette palette;
      try {
        volume = SchematicReaders.read(format, Files.readAllBytes(path), () -> legacy);
        palette = PreviewPalette.resolve(volume);
      } catch (VolumeFormatException exception) {
        reporter.publishEntry(name, "unreadable: " + exception.getMessage());
        continue;
      }
      for (int index = 0; index < palette.states().length; index++) {
        String blockId = palette.blockIds()[index];
        if (palette.known()[index]
            || blockId == null
            || !StandInBlocks.namespace(blockId).equals(VANILLA_NAMESPACE)) {
          continue;
        }
        long count = volume.paletteCounts()[index];
        total += count;
        left.merge(volume.dataVersion() + " " + volume.palette().get(index), count, Long::sum);
      }
    }
    left.forEach((state, count) -> reporter.publishEntry(state, Long.toString(count)));
    reporter.publishEntry("vanilla cells drawn as stand-ins", Long.toString(total));
    assertEquals(0, total, "vanilla blocks drawn as stand-ins");
  }

  private static List<Path> designFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(FILES)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static @Nullable SchematicFormat formatOf(String name) {
    return SchematicFormat.fromExtension(name.substring(name.lastIndexOf('.') + 1)).orElse(null);
  }

  @Test
  void blocksOfModsAreNeverDrawnAsAir(TestReporter reporter) throws IOException {
    assumeTrue(ALL_FILES, "every design only with -Dschematicvault.allFiles=true");
    LegacyBlocks legacy = LegacyBlocks.load();
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(FILES)) {
      paths = walk.filter(Files::isRegularFile).sorted().toList();
    }
    int designs = 0;
    long cells = 0;
    long drawnAsAir = 0;
    // Namespace -> cells per match kind (suffix, table, placeholder).
    Map<String, long[]> byNamespace = new TreeMap<>();
    for (Path path : paths) {
      String name = path.getFileName().toString();
      SchematicFormat format =
          SchematicFormat.fromExtension(name.substring(name.lastIndexOf('.') + 1)).orElse(null);
      if (format == null) {
        continue;
      }
      PreviewPalette palette;
      VoxelVolume volume;
      try {
        volume = SchematicReaders.read(format, Files.readAllBytes(path), () -> legacy);
        palette = PreviewPalette.resolve(volume);
      } catch (VolumeFormatException exception) {
        reporter.publishEntry(name, "unreadable: " + exception.getMessage());
        continue;
      }
      if (palette.standIns().isEmpty()) {
        continue;
      }
      designs++;
      cells += palette.standInBlocks();
      for (int index = 0; index < palette.states().length; index++) {
        String blockId = palette.blockIds()[index];
        if (palette.known()[index] || blockId == null) {
          continue;
        }
        long count = volume.paletteCounts()[index];
        if (palette.states()[index].isAir()) {
          drawnAsAir += count;
        }
        StandInBlocks.Match match =
            StandInBlocks.of(blockId, id -> BlockEdits.block(id) != null).match();
        byNamespace
                .computeIfAbsent(
                    StandInBlocks.namespace(blockId),
                    unused -> new long[StandInBlocks.Match.values().length])[match.ordinal()] +=
            count;
      }
      reporter.publishEntry(name.substring(0, 8), palette.standIns().toString());
    }
    reporter.publishEntry("designs with blocks of mods", Integer.toString(designs));
    reporter.publishEntry("cells drawn as stand-ins (air before)", Long.toString(cells));
    reporter.publishEntry("cells drawn as air", Long.toString(drawnAsAir));
    for (Map.Entry<String, long[]> entry : byNamespace.entrySet()) {
      long[] counts = entry.getValue();
      reporter.publishEntry(
          entry.getKey(),
          "suffix "
              + counts[StandInBlocks.Match.SUFFIX.ordinal()]
              + ", table "
              + counts[StandInBlocks.Match.TABLE.ordinal()]
              + ", placeholder "
              + counts[StandInBlocks.Match.PLACEHOLDER.ordinal()]);
    }
    assertEquals(0, drawnAsAir, "blocks of mods drawn as air");
    assertFalse(designs == 0, "no design with blocks of mods found");
  }
}
