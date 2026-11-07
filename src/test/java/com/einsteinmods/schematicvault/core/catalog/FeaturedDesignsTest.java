package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** {@link CatalogIndex#featured}: best per difficulty, fill-in, no duplicates, filters kept. */
class FeaturedDesignsTest {
  private static final String DAY = "2026-10-01T00:00:00Z";
  private static final String LATER = "2026-10-02T00:00:00Z";
  private static final int NONE = SchematicSummary.NO_POPULARITY;

  private static SchematicSummary design(
      String id, @Nullable Difficulty difficulty, int popularity, String category) {
    return design(id, difficulty, popularity, category, DAY);
  }

  private static SchematicSummary design(
      String id,
      @Nullable Difficulty difficulty,
      int popularity,
      String category,
      String updatedAt) {
    return DesignScoreTest.scored(
        id, difficulty, false, null, 0, false, null, popularity, category, updatedAt);
  }

  private static CatalogIndex index(SchematicSummary... designs) {
    return CatalogIndex.of(List.of(designs), LocaleTexts.NONE);
  }

  private static List<String> featuredIds(CatalogIndex index, CatalogFilter filter) {
    List<String> ids = new ArrayList<>();
    for (int pick : index.featured(index.filter(filter))) {
      ids.add(index.get(pick).id());
    }
    return ids;
  }

  @Test
  void bestOfEachDifficultyEasyFirst() {
    CatalogIndex index =
        index(
            design("hard-low", Difficulty.HARD, 10, "farms"),
            design("easy-low", Difficulty.EASY, 20, "farms"),
            design("hard-top", Difficulty.HARD, 90, "farms"),
            design("medium", Difficulty.MEDIUM, 30, "farms"),
            design("easy-top", Difficulty.EASY, 80, "farms"));
    assertArrayEqualsIds(
        List.of("easy-top", "medium", "hard-top"), featuredIds(index, CatalogFilter.NONE));
  }

  @Test
  void missingDifficultyTakesTheNextBestWithoutDuplicates() {
    CatalogIndex index =
        index(
            design("hard-1", Difficulty.HARD, 90, "farms"),
            design("hard-2", Difficulty.HARD, 80, "farms"),
            design("hard-3", Difficulty.HARD, 70, "farms"),
            design("easy-1", Difficulty.EASY, 10, "farms"),
            design("hard-4", Difficulty.HARD, 60, "farms"));
    // No medium design: its slot takes the best design left, a second hard one.
    assertArrayEqualsIds(
        List.of("easy-1", "hard-2", "hard-1"), featuredIds(index, CatalogFilter.NONE));
    CatalogIndex onlyHard =
        index(
            design("hard-1", Difficulty.HARD, 90, "farms"),
            design("hard-2", Difficulty.HARD, 80, "farms"),
            design("hard-3", Difficulty.HARD, 70, "farms"),
            design("hard-4", Difficulty.HARD, 60, "farms"));
    assertArrayEqualsIds(
        List.of("hard-2", "hard-3", "hard-1"), featuredIds(onlyHard, CatalogFilter.NONE));
  }

  @Test
  void unratedDifficultyOnlyFillsIn() {
    CatalogIndex index =
        index(
            design("unrated", null, 100, "farms"),
            design("easy", Difficulty.EASY, 10, "farms"),
            design("medium", Difficulty.MEDIUM, 10, "farms"),
            design("hard", Difficulty.HARD, 10, "farms"));
    assertArrayEqualsIds(List.of("easy", "medium", "hard"), featuredIds(index, CatalogFilter.NONE));
  }

  @Test
  void fewerDesignsGiveFewerCards() {
    CatalogIndex index =
        index(
            design("hard", Difficulty.HARD, 10, "farms"),
            design("easy", Difficulty.EASY, 5, "farms"));
    assertArrayEqualsIds(List.of("easy", "hard"), featuredIds(index, CatalogFilter.NONE));
    assertArrayEquals(new int[0], index.featured(new int[0]));
  }

  @Test
  void respectsCategorySearchAndFilters() {
    CatalogIndex index =
        index(
            design("iron-easy", Difficulty.EASY, 50, "farms/mob/iron"),
            design("gold-easy", Difficulty.EASY, 99, "farms/mob/gold"),
            design("iron-hard", Difficulty.HARD, 40, "farms/mob/iron"),
            design("castle", Difficulty.MEDIUM, 100, "builds"));
    assertArrayEqualsIds(
        List.of("gold-easy", "iron-easy", "iron-hard"),
        featuredIds(index, CatalogFilter.NONE.withCategory("farms")));
    assertArrayEqualsIds(
        List.of("iron-easy", "iron-hard"),
        featuredIds(index, CatalogFilter.NONE.withCategory("farms/mob/iron")));
    assertArrayEqualsIds(
        List.of("iron-easy", "iron-hard"), featuredIds(index, CatalogFilter.NONE.withText("iron")));
    assertArrayEqualsIds(
        List.of("castle", "iron-hard"),
        featuredIds(index, CatalogFilter.NONE.toggled(Difficulty.HARD).toggled(Difficulty.MEDIUM)));
  }

  @Test
  void tiesGoToTheNewestThenTheId() {
    CatalogIndex index =
        index(
            design("b", Difficulty.EASY, NONE, "farms", DAY),
            design("c", Difficulty.EASY, NONE, "farms", LATER),
            design("a", Difficulty.MEDIUM, NONE, "farms", DAY),
            design("d", Difficulty.MEDIUM, NONE, "farms", DAY));
    assertArrayEqualsIds(List.of("c", "a", "b"), featuredIds(index, CatalogFilter.NONE));
  }

  private static void assertArrayEqualsIds(List<String> expected, List<String> actual) {
    assertArrayEquals(expected.toArray(), actual.toArray());
  }
}
