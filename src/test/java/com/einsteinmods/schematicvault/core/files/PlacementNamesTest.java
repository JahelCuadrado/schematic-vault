package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlacementNamesTest {
  @Test
  void usesTheTitleAsShown() {
    assertEquals("Iron Farm (v2)", PlacementNames.of("Iron Farm (v2)", "iron_farm.litematic"));
    assertEquals("铁傀儡农场", PlacementNames.of("铁傀儡农场", "a.schem"));
  }

  @Test
  void blankTitleFallsBackToTheFileNameWithoutExtension() {
    assertEquals("Iron Farm (2)", PlacementNames.of("  ", "Iron Farm (2).schem"));
    assertEquals("farm.exe", PlacementNames.of("", "farm.exe.nbt"));
    assertEquals(".nbt", PlacementNames.of("", ".nbt"));
    assertEquals(SafeFileNames.FALLBACK_BASE, PlacementNames.of("\n", " "));
  }

  @Test
  void controlCharactersAndRunsOfSpacesBecomeOneSpace() {
    assertEquals("Big Farm v2", PlacementNames.of("  Big\n\tFarm   v2 \r\n", "x.nbt"));
    assertEquals("a b", PlacementNames.of("a\u0000b", "x.nbt"));
  }

  @Test
  void longNamesAreCutByCodePoints() {
    String emoji = "🌾";
    String name = PlacementNames.of(emoji.repeat(PlacementNames.MAX_LENGTH + 5), "x.nbt");
    assertEquals(PlacementNames.MAX_LENGTH, name.codePointCount(0, name.length()));
    assertEquals(emoji.repeat(PlacementNames.MAX_LENGTH), name);
  }
}
