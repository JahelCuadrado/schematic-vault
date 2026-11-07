package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogLeafCategoryTest {
  private static final CategoryNode IRON =
      new CategoryNode("farms/mob/iron", "Iron", 49, List.of());
  private static final CategoryNode MOB = new CategoryNode("farms/mob", "Mob", 378, List.of(IRON));
  private static final CategoryNode FARMS = new CategoryNode("farms", "Farms", 940, List.of(MOB));
  private static final CategoryNode OTHER = new CategoryNode("other", "Other", 3, List.of());
  private static final Catalog CATALOG =
      new Catalog(
          "revision",
          "2026-10-30T00:00:00Z",
          List.of(),
          List.of(FARMS, OTHER),
          new LocaleTexts("en", Map.of(), Map.of(), Map.of()));

  @Test
  void aCategoryWithoutSubcategoriesIsALeaf() {
    assertTrue(CATALOG.isLeafCategory("farms/mob/iron"));
    assertTrue(CATALOG.isLeafCategory("other"));
  }

  @Test
  void parentsAreNotLeaves() {
    assertFalse(CATALOG.isLeafCategory("farms"));
    assertFalse(CATALOG.isLeafCategory("farms/mob"));
  }

  @Test
  void allAndUnknownPathsAreNotLeaves() {
    assertFalse(CATALOG.isLeafCategory(""));
    assertFalse(CATALOG.isLeafCategory("farms/mob/gold"));
  }
}
