package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogRefineTest {
  static final CatalogIndex INDEX =
      CatalogIndex.of(
          List.of(
              Samples.schematic(
                  "a", "Iron Farm", "farms/mob/iron", 900, "2026-10-01T00:00:00Z", "iron-golem"),
              Samples.schematic(
                  "b", "Gold farm", "farms/mob/gold", 300, "2026-10-03T00:00:00Z", "gold"),
              Samples.schematic(
                  "c", "Castillo Pequeño", "builds/castles", 5000, "2026-10-02T00:00:00Z"),
              Samples.schematic("d", "Ironwood Lodge", "farms/mobile", 100, "2026-09-01T00:00:00Z"),
              Samples.schematic(
                  "e", "Piñata Iron Trap", "farms/mob/iron", 2000, "2026-08-01T00:00:00Z"),
              Samples.schematic(
                  "f", "Farm of farms", "farms", 40, "2026-07-01T00:00:00Z", "slime-blocks"),
              Samples.schematic(
                  "g", "Gold Iron Combo", "farms/mob", 1200, "2026-06-01T00:00:00Z", "gold")),
          new LocaleTexts(
              "es",
              Map.of(),
              Map.of("a", "Granja de hierro", "e", "Trampa de hierro piñata"),
              Map.of("iron-golem", "Gólem de hierro", "slime-blocks", "Bloques de slime")));

  /** Queries typed, edited and replaced; every ordered pair of them is checked. */
  private static final String[] QUERIES = {
    "",
    "i",
    "ir",
    "iro",
    "iron",
    "iron ",
    "iron f",
    "iron fa",
    "iron farm",
    "farm iron",
    "iron gold",
    "IRON",
    "iron iron",
    "g",
    "go",
    "gold",
    "gold i",
    "pin",
    "piñ",
    "piñata",
    "pinata",
    "de",
    "de hierro",
    "granja de hierro",
    "slime",
    "slime-b",
    "señor",
    "diamond",
    "  iron   farm  "
  };

  private static List<CatalogFilter> filters() {
    CatalogFilter[] bases = {
      CatalogFilter.NONE,
      CatalogFilter.NONE.withCategory("farms/mob"),
      CatalogFilter.NONE.withSort(SortOrder.SMALLEST),
      CatalogFilter.NONE.withSort(SortOrder.LARGEST).toggled(SizeBucket.SMALL),
      CatalogFilter.NONE.toggled(Difficulty.MEDIUM)
    };
    List<CatalogFilter> filters = new ArrayList<>();
    for (CatalogFilter base : bases) {
      for (String query : QUERIES) {
        filters.add(base.withText(query));
      }
    }
    return filters;
  }

  @Test
  void typingOnRefinesTheLastFilter() {
    CatalogFilter iron = CatalogFilter.NONE.withText("iron");
    assertTrue(iron.withText("iro").refines(iron.withText("ir")), "a letter appended");
    assertTrue(iron.withText("iron f").refines(iron), "a word added");
    assertTrue(iron.withText("iron ").refines(iron), "a trailing space");
    assertTrue(iron.withText("IRON").refines(iron), "only the case changed");
    assertTrue(iron.withText("farm iron").refines(iron.withText("iron farm")), "words reordered");
    assertTrue(iron.withText("pin").refines(iron.withText("piñ")), "accents are stripped");
    assertTrue(iron.refines(CatalogFilter.NONE), "from an empty search");
    assertTrue(iron.refines(iron), "the same filter");
  }

  @Test
  void anythingElseSearchesTheWholeCatalog() {
    CatalogFilter iron = CatalogFilter.NONE.withText("iron");
    assertFalse(iron.withText("iro").refines(iron), "a letter deleted");
    assertFalse(iron.withText("").refines(iron), "the search cleared");
    assertFalse(iron.withText("iron gold").refines(iron.withText("iron farm")), "a word replaced");
    assertFalse(iron.withText("gold").refines(iron), "another word");
    assertFalse(iron.withCategory("farms").refines(iron), "a category chosen");
    assertFalse(iron.refines(iron.withCategory("farms")), "the category cleared");
    assertFalse(iron.withSort(SortOrder.SMALLEST).refines(iron), "another order");
    assertFalse(iron.toggled(Difficulty.HARD).refines(iron), "a difficulty chosen");
    assertFalse(iron.withVerifiedOnly(true).refines(iron), "verified only");
    assertFalse(iron.toggled(SizeBucket.SMALL).refines(iron), "a size chosen");
    assertFalse(iron.toggled(WidthBucket.ONE).refines(iron), "a width chosen");
  }

  @Test
  void refinedMatchesEqualAFullSearchForEveryRefiningPair() {
    int refined = 0;
    for (CatalogFilter previous : filters()) {
      int[] previousMatches = INDEX.filter(previous);
      for (CatalogFilter next : filters()) {
        if (next.refines(previous)) {
          assertArrayEquals(
              INDEX.filter(next),
              INDEX.refine(next, previousMatches),
              "'" + previous.text() + "' -> '" + next.text() + "' " + next);
          refined++;
        }
      }
    }
    assertTrue(refined > QUERIES.length * 2, refined + " refined pairs");
  }

  /** Pairs that do not refine: searching only the previous matches would give another result. */
  @Test
  void refiningPairsThatDoNotRefineWouldBeWrong() {
    CatalogFilter iron = CatalogFilter.NONE.withText("iron");
    assertRefiningIsWrong(iron.withText("gold"), iron.withText("go"));
    assertRefiningIsWrong(iron.withCategory("farms/mob"), iron);
    assertRefiningIsWrong(iron.withText("iron farm"), iron.withText("iron gold"));
    assertRefiningIsWrong(iron, iron.withSort(SortOrder.SMALLEST));
    assertRefiningIsWrong(iron.toggled(SizeBucket.SMALL), iron);
  }

  private static void assertRefiningIsWrong(CatalogFilter previous, CatalogFilter next) {
    assertFalse(next.refines(previous));
    assertFalse(
        Arrays.equals(INDEX.filter(next), INDEX.refine(next, INDEX.filter(previous))),
        previous + " -> " + next);
  }
}
