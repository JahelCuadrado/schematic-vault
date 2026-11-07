package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTextTest {
  @Test
  void lowercaseWithoutAccentsAndExtraSpaces() {
    assertEquals("granja de hierro", SearchText.normalize("  Granja   de HIÉRRO "));
    assertEquals("pinata", SearchText.normalize("Piñata"));
    assertEquals("铁傀儡", SearchText.normalize("铁傀儡"));
  }

  @Test
  void termsAreTheDistinctWords() {
    assertEquals(List.of("iron", "farm"), SearchText.terms("Iron  farm IRON"));
    assertEquals(List.of(), SearchText.terms("   "));
  }

  @Test
  void distinctWordsKeepEachWordOnce() {
    assertEquals(
        "granja de hierro iron-golem farm golem",
        SearchText.distinctWords("granja de hierro iron-golem farm hierro golem farm"));
    assertEquals("", SearchText.distinctWords(""));
  }
}
