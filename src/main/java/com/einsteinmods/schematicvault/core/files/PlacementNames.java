package com.einsteinmods.schematicvault.core.files;

/**
 * The name a design's Litematica placement gets: the design's title as the vault shows it (or the
 * name the player gave an edited copy), else the file's name without its extension. Line breaks and
 * other control characters become spaces, runs of spaces collapse, and the name is cut to {@value
 * #MAX_LENGTH} code points so Litematica's placement list stays readable.
 */
public final class PlacementNames {
  /** Longest name kept, in code points: as long as a saved file's base name. */
  public static final int MAX_LENGTH = SafeFileNames.MAX_BASE_LENGTH;

  private static final char SPACE = ' ';
  private static final char EXTENSION_DOT = '.';

  private PlacementNames() {}

  /**
   * @param title the design's title, or the typed name of an edited copy; may be blank
   * @param fileName the loaded file's name, with its extension
   */
  public static String of(String title, String fileName) {
    String name = clean(title);
    if (name.isEmpty()) {
      int dot = fileName.lastIndexOf(EXTENSION_DOT);
      name = clean(dot > 0 ? fileName.substring(0, dot) : fileName);
    }
    return name.isEmpty() ? SafeFileNames.FALLBACK_BASE : name;
  }

  /** {@code text} on one line, spaces collapsed, trimmed and cut to {@link #MAX_LENGTH}. */
  private static String clean(String text) {
    StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
    int kept = 0;
    for (int offset = 0; offset < text.length() && kept < MAX_LENGTH; ) {
      int codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      boolean blank = Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
      if (blank && (cleaned.isEmpty() || cleaned.charAt(cleaned.length() - 1) == SPACE)) {
        continue;
      }
      cleaned.appendCodePoint(blank ? SPACE : codePoint);
      kept++;
    }
    return cleaned.toString().strip();
  }
}
