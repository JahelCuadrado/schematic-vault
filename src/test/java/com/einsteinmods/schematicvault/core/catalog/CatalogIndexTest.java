package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogIndexTest {
  private static final CatalogIndex INDEX =
      CatalogIndex.of(
          List.of(
              Samples.schematic(
                  "a", "Iron Farm", "farms/mob/iron", 900, "2026-10-01T00:00:00Z", "iron-golem"),
              Samples.schematic(
                  "b", "Gold farm", "farms/mob/gold", 300, "2026-10-03T00:00:00Z", "gold"),
              Samples.schematic(
                  "c", "Castillo Pequeño", "builds/castles", 5000, "2026-10-02T00:00:00Z"),
              Samples.schematic(
                  "d", "Ironwood Lodge", "farms/mobile", 100, "2026-09-01T00:00:00Z")),
          new LocaleTexts(
              "es",
              Map.of(),
              Map.of("a", "Granja de hierro", "d", "Ironwood Lodge"),
              Map.of("iron-golem", "Gólem de hierro")));

  private static int[] filter(String query, String categoryPath, SortOrder order) {
    return INDEX.filter(
        CatalogFilter.NONE.withText(query).withCategory(categoryPath).withSort(order));
  }

  @Test
  void emptyQueryListsEverythingNewestFirst() {
    assertArrayEquals(new int[] {1, 2, 0, 3}, filter("", "", SortOrder.NEWEST));
  }

  @Test
  void sizeOrdersByBlockCount() {
    assertArrayEquals(new int[] {3, 1, 0, 2}, filter("", "", SortOrder.SMALLEST));
    assertArrayEquals(new int[] {2, 0, 1, 3}, filter("", "", SortOrder.LARGEST));
  }

  @Test
  void everyWordMustMatchTitleAuthorOrTag() {
    assertArrayEquals(new int[] {0, 3}, filter("iron", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("IRON farm", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("golem", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {2}, filter("pequeno", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {1, 2, 0, 3}, filter("senor", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {}, filter("diamond", "", SortOrder.NEWEST));
  }

  @Test
  void categoryMatchesItselfAndItsChildrenOnly() {
    assertArrayEquals(new int[] {1, 0}, filter("", "farms/mob", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("", "farms/mob/iron", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("iron", "farms/mob", SortOrder.NEWEST));
    assertTrue(CatalogIndex.inCategory("farms/mob", "farms/mob"));
    assertFalse(CatalogIndex.inCategory("farms/mobile", "farms/mob"));
    assertTrue(CatalogIndex.inCategory("anything", ""));
  }

  @Test
  void showsTheLocaleTitleElseTheOriginal() {
    assertEquals("Granja de hierro", INDEX.title(0));
    assertEquals("Gold farm", INDEX.title(1));
  }

  @Test
  void searchesTheShownAndOriginalTitleAndTheShownTagNames() {
    assertArrayEquals(new int[] {0}, filter("granja hierro", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("iron farm", "", SortOrder.NEWEST));
    assertArrayEquals(new int[] {0}, filter("golem de hierro", "", SortOrder.NEWEST));
  }
}
