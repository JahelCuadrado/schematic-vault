package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarkdownTextTest {
  @Test
  void headingMarkersAreDropped() {
    assertEquals(
        "Features\nFast\nTitle", MarkdownText.plain("###### Features\n# Fast\r\n  ## Title"));
    assertEquals("Empty heading below\n\nText", MarkdownText.plain("Empty heading below\n#\nText"));
  }

  @Test
  void boldItalicAndCodeMarkersAreDropped() {
    assertEquals(
        "Needs Carpet and the autoCrafting rule, very fast",
        MarkdownText.plain("Needs **Carpet** and the `autoCrafting` rule, _very_ *fast*"));
    assertEquals("bold italic", MarkdownText.plain("__bold__ ***italic***"));
  }

  @Test
  void underscoresInsideWordsAndHashesInsideLinesStay() {
    assertEquals(
        "set auto_crafting_table to true, slot #3",
        MarkdownText.plain("set auto_crafting_table to true, slot #3"));
  }

  @Test
  void bulletsAndBlankLinesAreTidied() {
    assertEquals("- one\n  - two\n\nend", MarkdownText.plain("* one  \n  + two\n\n\n\nend\n"));
  }

  @Test
  void plainTextIsUnchanged() {
    assertEquals(
        "Produces 64,000 pistons per hour", MarkdownText.plain("Produces 64,000 pistons per hour"));
  }
}
