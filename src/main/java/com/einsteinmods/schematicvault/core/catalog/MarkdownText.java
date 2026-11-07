package com.einsteinmods.schematicvault.core.catalog;

import java.util.regex.Pattern;

/**
 * Authors' notes as plain text: the Markdown markers they type ({@code ###### Features}, {@code
 * **bold**}, {@code _italic_}, {@code `code`}) are dropped and the words kept. Underscores inside a
 * word ({@code snake_case}) stay; a {@code * item} bullet becomes {@code - item}.
 */
public final class MarkdownText {
  private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n?");
  private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}(?:[ \\t]+|$)");
  private static final Pattern BULLET = Pattern.compile("(?m)^([ \\t]*)[*+][ \\t]+");
  private static final Pattern ASTERISKS_AND_BACKTICKS = Pattern.compile("[*`]+");

  /** Underscores at the start or end of a word: emphasis, not part of a name. */
  private static final Pattern EMPHASIS_UNDERSCORES =
      Pattern.compile("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])");

  private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");
  private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

  private static final String NEW_LINE = "\n";
  private static final String BULLET_REPLACEMENT = "$1- ";
  private static final String PARAGRAPH_BREAK = "\n\n";

  private MarkdownText() {}

  public static String plain(String markdown) {
    String text = LINE_BREAK.matcher(markdown).replaceAll(NEW_LINE);
    text = HEADING.matcher(text).replaceAll("");
    text = BULLET.matcher(text).replaceAll(BULLET_REPLACEMENT);
    text = ASTERISKS_AND_BACKTICKS.matcher(text).replaceAll("");
    text = EMPHASIS_UNDERSCORES.matcher(text).replaceAll("");
    text = TRAILING_SPACES.matcher(text).replaceAll("");
    return BLANK_LINES.matcher(text).replaceAll(PARAGRAPH_BREAK).strip();
  }
}
