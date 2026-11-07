package com.einsteinmods.schematicvault.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The game's data fixer on old block states, through the real fixers and block registry. */
class PreviewPaletteFixerTest {
  private static final int NO_VERSION = 0;
  private static final int NEGATIVE_VERSION = -1;
  private static final int MINECRAFT_1_19_2 = 3120;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static BlockStateString.Parsed fix(int dataVersion, String state) {
    return PreviewPalette.Fixer.from(dataVersion).fix(state);
  }

  @Test
  void aFileWithoutVersionHasItsOldNamesFixed() {
    assertEquals("minecraft:cobweb", fix(NO_VERSION, "minecraft:web").name());
    assertEquals(
        "minecraft:birch_log", fix(NO_VERSION, "minecraft:log[axis=y,variant=birch]").name());
    assertEquals(
        "minecraft:spruce_slab",
        fix(NO_VERSION, "minecraft:wooden_slab[half=bottom,variant=spruce]").name());
    assertEquals("minecraft:cobweb", fix(1, "minecraft:web").name());
  }

  @Test
  void whatTheFlatteningDropsKeepsTheFilesValue() {
    assertEquals(
        "true",
        fix(NO_VERSION, "minecraft:glass_pane[east=false,south=true,north=true,west=false]")
            .properties()
            .get("north"));
    assertEquals(
        "west",
        fix(
                NO_VERSION,
                "minecraft:iron_door[hinge=right,half=upper,powered=false,facing=west,open=false]")
            .properties()
            .get("facing"));
  }

  @Test
  void aValueTheFlatteningChangedIsNotBroughtBack() {
    assertEquals(
        "floor",
        fix(NO_VERSION, "minecraft:lever[powered=false,facing=up_z]").properties().get("face"));
  }

  @Test
  void aNegativeVersionIsNotFixed() {
    assertEquals("minecraft:snow", fix(NEGATIVE_VERSION, "minecraft:snow").name());
  }

  @Test
  void aFileDeclaring1_12KeepsTheFixersOutput() {
    assertEquals(
        "false",
        fix(1343, "minecraft:glass_pane[east=false,south=true,north=true,west=false]")
            .properties()
            .get("north"));
  }

  @Test
  void aDeclaredVersionKeepsItsNames() {
    assertEquals("minecraft:snow", fix(MINECRAFT_1_19_2, "minecraft:snow[layers=1]").name());
  }
}
