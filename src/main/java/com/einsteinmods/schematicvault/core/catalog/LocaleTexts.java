package com.einsteinmods.schematicvault.core.catalog;

import java.util.Map;

/**
 * The catalog's texts in one API language, from {@code v1/locales/{language}.json}: what the list,
 * the search, the category picker and the detail panel show instead of the original texts.
 *
 * @param language API language (e.g. {@code es}, {@code zh-CN}); {@link #NO_LANGUAGE} when no
 *     locale could be read, so the next open asks for it again
 * @param categoryNames shown name by category path; paths missing here use the English name
 * @param titles shown title by design id; ids missing here use the original title
 * @param tagNames shown name by tag id; tags missing here show their id
 */
public record LocaleTexts(
    String language,
    Map<String, String> categoryNames,
    Map<String, String> titles,
    Map<String, String> tagNames) {
  /** Language of texts that come from no locale: matches no API language. */
  public static final String NO_LANGUAGE = "";

  /** No locale: every text is the original one. */
  public static final LocaleTexts NONE = new LocaleTexts(NO_LANGUAGE, Map.of(), Map.of(), Map.of());

  public LocaleTexts {
    categoryNames = Map.copyOf(categoryNames);
    titles = Map.copyOf(titles);
    tagNames = Map.copyOf(tagNames);
  }

  /** Shown title of a design: its locale title, else the original one. */
  public String title(SchematicSummary schematic) {
    return titles.getOrDefault(schematic.id(), schematic.title());
  }

  /** Shown name of a tag: its locale name, else its id. */
  public String tagName(String tag) {
    return tagNames.getOrDefault(tag, tag);
  }

  /** Shown name of a category: its locale name, else the English one. */
  public String categoryName(CategoryNode node) {
    return categoryNames.getOrDefault(node.path(), node.name());
  }
}
