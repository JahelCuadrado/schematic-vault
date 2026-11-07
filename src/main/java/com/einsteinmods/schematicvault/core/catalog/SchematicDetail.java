package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A design's own record, fetched when it is selected: texts, credits, source and the files to
 * download.
 *
 * @param original the author's title and description, in the language they wrote them
 * @param summary the author's own notes, untranslated and often Markdown: never the description
 * @param translations title and description by API language ({@code es}, {@code zh-CN})
 * @param sourceUrl http(s) page of the design at its source, or null
 * @param primaryFileId id of the file "Download" fetches, or null to use the first file
 * @param files only files in a format the mod saves, in the API's order
 */
public record SchematicDetail(
    String id,
    DetailText original,
    String summary,
    Map<String, DetailText> translations,
    List<SchematicAuthor> authors,
    @Nullable String sourceName,
    @Nullable String sourceUrl,
    @Nullable String primaryFileId,
    List<SchematicFile> files) {
  public SchematicDetail {
    translations = Map.copyOf(translations);
    authors = List.copyOf(authors);
    files = List.copyOf(files);
  }

  /** The title in {@code language}, else in English, else the original; empty when none. */
  public Optional<String> title(String language) {
    return text(language, DetailText::title);
  }

  /** The description in {@code language}, else in English, else the original; empty when none. */
  public Optional<String> description(String language) {
    return text(language, DetailText::description);
  }

  /**
   * The author's notes when they add something: not blank and neither the shown nor the original
   * description (it would only repeat it).
   */
  public Optional<String> authorNotes(String language) {
    String notes = summary.strip();
    if (notes.isEmpty()
        || notes.equals(original.description().strip())
        || description(language).map(shown -> notes.equals(shown.strip())).orElse(false)) {
      return Optional.empty();
    }
    return Optional.of(notes);
  }

  /** The file "Download" fetches: the primary one, else the first; empty with no files. */
  public Optional<SchematicFile> primaryFile() {
    for (SchematicFile file : files) {
      if (file.id().equals(primaryFileId)) {
        return Optional.of(file);
      }
    }
    return files.isEmpty() ? Optional.empty() : Optional.of(files.getFirst());
  }

  private Optional<String> text(String language, Function<DetailText, String> field) {
    String translated = nonBlank(translations.get(language), field);
    if (translated == null) {
      translated = nonBlank(translations.get(ApiLanguages.FALLBACK), field);
    }
    if (translated == null) {
      translated = nonBlank(original, field);
    }
    return Optional.ofNullable(translated);
  }

  private static @Nullable String nonBlank(
      @Nullable DetailText text, Function<DetailText, String> field) {
    if (text == null) {
      return null;
    }
    String value = field.apply(text);
    return value.isBlank() ? null : value;
  }
}
