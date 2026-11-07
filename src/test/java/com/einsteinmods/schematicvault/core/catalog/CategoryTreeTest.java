package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CategoryTreeTest {
  /** All, farms ▸ (mob ▸ (iron), crop), storage. */
  private static final List<CategoryRow> ROWS =
      List.of(
          CategoryRow.all(1000),
          new CategoryRow("farms", "Farms", 0, 940),
          new CategoryRow("farms/mob", "Mob farms", 1, 378),
          new CategoryRow("farms/mob/iron", "Iron farms", 2, 50),
          new CategoryRow("farms/crop", "Crop farms", 1, 100),
          new CategoryRow("storage", "Storage", 0, 60));

  @Test
  void ancestorsListTheCategoriesAboveTopFirst() {
    assertEquals(List.of("farms", "farms/mob"), CategoryTree.ancestors("farms/mob/iron"));
    assertEquals(List.of(), CategoryTree.ancestors("farms"));
    assertEquals(List.of(), CategoryTree.ancestors(""));
  }

  @Test
  void onlyRowsFollowedByDeeperOnesHaveChildren() {
    assertFalse(CategoryTree.hasChildren(ROWS, 0));
    assertTrue(CategoryTree.hasChildren(ROWS, 1));
    assertTrue(CategoryTree.hasChildren(ROWS, 2));
    assertFalse(CategoryTree.hasChildren(ROWS, 3));
    assertFalse(CategoryTree.hasChildren(ROWS, 4));
    assertFalse(CategoryTree.hasChildren(ROWS, 5));
  }

  @Test
  void collapsedTreeShowsAllAndTheTopCategories() {
    assertArrayEquals(new int[] {0, 1, 5}, CategoryTree.visible(ROWS, Set.of()));
  }

  @Test
  void expandingAParentShowsOnlyItsDirectChildren() {
    assertArrayEquals(new int[] {0, 1, 2, 4, 5}, CategoryTree.visible(ROWS, Set.of("farms")));
  }

  @Test
  void aChildExpandedUnderACollapsedParentStaysHidden() {
    assertArrayEquals(new int[] {0, 1, 5}, CategoryTree.visible(ROWS, Set.of("farms/mob")));
  }

  @Test
  void theSelectedPathExpandedShowsTheSelectedRow() {
    Set<String> expanded = Set.copyOf(CategoryTree.ancestors("farms/mob/iron"));
    assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, CategoryTree.visible(ROWS, expanded));
  }
}
