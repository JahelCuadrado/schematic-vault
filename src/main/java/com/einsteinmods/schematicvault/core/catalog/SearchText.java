package com.einsteinmods.schematicvault.core.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text as the search compares it: lowercase, accents stripped ({@code Piñata} and {@code pinata}
 * match), runs of whitespace as one space.
 */
public final class SearchText {
  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final String WORD_SEPARATOR = " ";

  private SearchText() {}

  public static String normalize(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
    return WHITESPACE.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /**
   * The words of a normalized text, each once, in first-seen order: a term without spaces matches
   * the result exactly when it matches {@code normalized}, over a shorter text.
   */
  public static String distinctWords(String normalized) {
    if (normalized.isEmpty()) {
      return normalized;
    }
    List<String> kept = new ArrayList<>();
    for (String word : normalized.split(WORD_SEPARATOR)) {
      if (!kept.contains(word)) {
        kept.add(word);
      }
    }
    return String.join(WORD_SEPARATOR, kept);
  }

  /** The normalized words of a query; every one must appear in a design's text. */
  public static List<String> terms(String query) {
    String normalized = normalize(query);
    if (normalized.isEmpty()) {
      return List.of();
    }
    List<String> terms = new ArrayList<>();
    for (String term : normalized.split(WORD_SEPARATOR)) {
      if (!terms.contains(term)) {
        terms.add(term);
      }
    }
    return List.copyOf(terms);
  }
}
