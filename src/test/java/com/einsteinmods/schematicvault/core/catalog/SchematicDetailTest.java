package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchematicDetailTest {
  private static final DetailText ORIGINAL = new DetailText("活塞工厂", "生产活塞。");

  private static SchematicDetail detail(
      DetailText original, String summary, Map<String, DetailText> translations) {
    return new SchematicDetail(
        "x", original, summary, translations, List.of(), null, null, null, List.of());
  }

  @Test
  void textsFallBackFromTheLanguageToEnglishToTheOriginal() {
    SchematicDetail detail =
        detail(
            ORIGINAL,
            "",
            Map.of(
                "es", new DetailText("Fábrica de pistones", ""),
                "en", new DetailText("Piston factory", "Makes pistons.")));
    assertEquals(Optional.of("Fábrica de pistones"), detail.title("es"));
    assertEquals(Optional.of("Makes pistons."), detail.description("es"));
    assertEquals(Optional.of("Piston factory"), detail.title("de"));
    assertEquals(Optional.of("活塞工厂"), detail("x", ORIGINAL).title("de"));
    assertEquals(Optional.of("生产活塞。"), detail("x", ORIGINAL).description("es"));
  }

  @Test
  void noTextAtAllIsEmpty() {
    SchematicDetail detail = detail(new DetailText("", " "), "", Map.of());
    assertEquals(Optional.empty(), detail.title("es"));
    assertEquals(Optional.empty(), detail.description("es"));
  }

  @Test
  void authorNotesOnlyWhenTheyAddSomething() {
    assertEquals(Optional.empty(), detail(" ", ORIGINAL).authorNotes("es"));
    assertEquals(Optional.empty(), detail("生产活塞。\n", ORIGINAL).authorNotes("es"));
    SchematicDetail repeatsTranslation =
        detail(ORIGINAL, "Hace pistones.", Map.of("es", new DetailText("", "Hace pistones.")));
    assertEquals(Optional.empty(), repeatsTranslation.authorNotes("es"));
    assertEquals(
        Optional.of("**64k** per hour"), detail(" **64k** per hour ", ORIGINAL).authorNotes("es"));
  }

  private static SchematicDetail detail(String summary, DetailText original) {
    return detail(original, summary, Map.of());
  }
}
