package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocaleTextsTest {
  private static final LocaleTexts SPANISH =
      new LocaleTexts(
          "es",
          Map.of("farms", "Granjas"),
          Map.of("a", "Granja de hierro"),
          Map.of("iron-golem", "Gólem de hierro"));

  @Test
  void titleIsTheLocaleOneElseTheOriginal() {
    assertEquals(
        "Granja de hierro",
        SPANISH.title(Samples.schematic("a", "Iron Farm", "farms", 1, "2026-10-01T00:00:00Z")));
    assertEquals(
        "铁傀儡农场",
        SPANISH.title(Samples.schematic("b", "铁傀儡农场", "farms", 1, "2026-10-01T00:00:00Z")));
  }

  @Test
  void tagNameIsTheLocaleOneElseTheId() {
    assertEquals("Gólem de hierro", SPANISH.tagName("iron-golem"));
    assertEquals("slime-blocks", SPANISH.tagName("slime-blocks"));
  }

  @Test
  void categoryNameIsTheLocaleOneElseTheEnglishOne() {
    assertEquals("Granjas", SPANISH.categoryName(new CategoryNode("farms", "Farms", 1, List.of())));
    assertEquals(
        "Builds", SPANISH.categoryName(new CategoryNode("builds", "Builds", 1, List.of())));
  }

  @Test
  void noLocaleShowsTheOriginalTexts() {
    assertEquals(
        "Iron Farm",
        LocaleTexts.NONE.title(
            Samples.schematic("a", "Iron Farm", "farms", 1, "2026-10-01T00:00:00Z")));
    assertEquals("iron-golem", LocaleTexts.NONE.tagName("iron-golem"));
  }
}
