package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogFilterTest {
  /** Index order: a (newest) .. e (oldest), so NEWEST lists 0, 1, 2, 3, 4. */
  private static final CatalogIndex INDEX =
      CatalogIndex.of(
          List.of(
              design("a", "Iron farm", "farms/mob/iron", 120, Difficulty.EASY, true, 1, "10"),
              design("b", "Gold farm", "farms/mob/gold", 4_000, Difficulty.HARD, false, 9, "09"),
              design("c", "Castle", "builds/castles", 80_000, Difficulty.MEDIUM, true, 40, "08"),
              design("d", "Sugar cane", "farms/crops", 300, null, false, 2, "07"),
              design("e", "Iron hall", "builds/houses", 29_999, Difficulty.HARD, false, 5, "06")),
          new LocaleTexts("en", Map.of(), Map.of(), Map.of()));

  private static SchematicSummary design(
      String id,
      String title,
      String category,
      long blocks,
      Difficulty difficulty,
      boolean verified,
      int width,
      String day) {
    return new SchematicSummary(
        id,
        title,
        List.of("Builder"),
        List.of(),
        category,
        null,
        "source",
        null,
        BlockSize.UNKNOWN,
        blocks,
        difficulty,
        verified,
        width,
        null,
        null,
        "2026-10-" + day + "T00:00:00Z",
        null,
        0,
        false,
        SchematicSummary.NO_POPULARITY,
        null,
        0,
        false);
  }

  @Test
  void noFilterListsEverything() {
    assertArrayEquals(new int[] {0, 1, 2, 3, 4}, INDEX.filter(CatalogFilter.NONE));
  }

  @Test
  void difficultyMatchesAnyChosenAndSkipsUnrated() {
    CatalogFilter hard = CatalogFilter.NONE.toggled(Difficulty.HARD);
    assertArrayEquals(new int[] {1, 4}, INDEX.filter(hard));
    assertArrayEquals(new int[] {0, 1, 4}, INDEX.filter(hard.toggled(Difficulty.EASY)));
    assertArrayEquals(
        new int[] {0, 1, 2, 4},
        INDEX.filter(
            CatalogFilter.NONE.withDifficulties(
                Set.of(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD))));
  }

  @Test
  void verifiedOnly() {
    assertArrayEquals(new int[] {0, 2}, INDEX.filter(CatalogFilter.NONE.withVerifiedOnly(true)));
  }

  @Test
  void sizeBucketsByBlockCount() {
    assertArrayEquals(new int[] {0}, INDEX.filter(CatalogFilter.NONE.toggled(SizeBucket.SMALL)));
    assertArrayEquals(new int[] {3}, INDEX.filter(CatalogFilter.NONE.toggled(SizeBucket.MEDIUM)));
    assertArrayEquals(new int[] {1, 4}, INDEX.filter(CatalogFilter.NONE.toggled(SizeBucket.LARGE)));
    assertArrayEquals(
        new int[] {0, 2},
        INDEX.filter(CatalogFilter.NONE.toggled(SizeBucket.SMALL).toggled(SizeBucket.HUGE)));
  }

  @Test
  void widthBuckets() {
    assertArrayEquals(new int[] {0}, INDEX.filter(CatalogFilter.NONE.toggled(WidthBucket.ONE)));
    assertArrayEquals(
        new int[] {1, 3, 4},
        INDEX.filter(
            CatalogFilter.NONE
                .toggled(WidthBucket.TWO)
                .toggled(WidthBucket.FOUR_TO_FIVE)
                .toggled(WidthBucket.SIX_TO_TEN)));
  }

  @Test
  void everyFilterMustMatch() {
    CatalogFilter filter =
        CatalogFilter.NONE
            .withCategory("farms")
            .toggled(Difficulty.HARD)
            .toggled(Difficulty.EASY)
            .toggled(SizeBucket.LARGE);
    assertArrayEquals(new int[] {1}, INDEX.filter(filter));
    assertArrayEquals(new int[] {}, INDEX.filter(filter.withVerifiedOnly(true)));
    assertArrayEquals(
        new int[] {0}, INDEX.filter(CatalogFilter.NONE.withText("iron").withVerifiedOnly(true)));
    assertArrayEquals(
        new int[] {4}, INDEX.filter(CatalogFilter.NONE.withText("iron").toggled(Difficulty.HARD)));
  }

  @Test
  void sortAppliesToTheFilteredDesigns() {
    CatalogFilter hard = CatalogFilter.NONE.toggled(Difficulty.HARD);
    assertArrayEquals(new int[] {1, 4}, INDEX.filter(hard.withSort(SortOrder.SMALLEST)));
    assertArrayEquals(new int[] {4, 1}, INDEX.filter(hard.withSort(SortOrder.LARGEST)));
  }

  @Test
  void toggleAddsThenRemoves() {
    CatalogFilter once = CatalogFilter.NONE.toggled(WidthBucket.THREE);
    assertEquals(Set.of(WidthBucket.THREE), once.widths());
    assertEquals(Set.of(), once.toggled(WidthBucket.THREE).widths());
  }

  @Test
  void countsChosenFineValuesOnly() {
    CatalogFilter filter =
        CatalogFilter.NONE
            .withCategory("farms")
            .withText("iron")
            .toggled(Difficulty.EASY)
            .toggled(Difficulty.HARD)
            .withVerifiedOnly(true)
            .toggled(SizeBucket.HUGE)
            .toggled(WidthBucket.ONE);
    assertEquals(5, filter.fineFilterCount());
    assertTrue(filter.isActive());
    assertFalse(CatalogFilter.NONE.withSort(SortOrder.LARGEST).isActive());
  }

  @Test
  void clearedKeepsOnlyTheOrder() {
    CatalogFilter filter =
        CatalogFilter.NONE
            .withSort(SortOrder.SMALLEST)
            .withCategory("farms")
            .withText("x")
            .toggled(SizeBucket.SMALL)
            .withVerifiedOnly(true);
    assertEquals(CatalogFilter.NONE.withSort(SortOrder.SMALLEST), filter.cleared());
  }
}
