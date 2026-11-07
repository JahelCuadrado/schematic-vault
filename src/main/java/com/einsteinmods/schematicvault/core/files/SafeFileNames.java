package com.einsteinmods.schematicvault.core.files;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * File names for downloads, built from the API's file name: only letters, digits and {@code -_.() }
 * survive (anything else becomes {@code _}), so a name can never leave the downloads folder, hide
 * its extension or clash with a reserved Windows device name. The extension always comes from the
 * file's format, never from the API name.
 */
public final class SafeFileNames {
  /** Longest base name kept, in code points: far below every file system's limit. */
  public static final int MAX_BASE_LENGTH = 64;

  public static final String FALLBACK_BASE = "schematic";

  private static final char REPLACEMENT = '_';
  private static final String ALLOWED_PUNCTUATION = "-_.() ";

  /** Highest " (n)" suffix tried before giving up on a free name. */
  private static final int MAX_COLLISION_SUFFIX = 9999;

  private static final Set<String> RESERVED_WINDOWS_NAMES =
      Set.of(
          "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
          "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  private SafeFileNames() {}

  /** {@code apiFileName} made safe, with the extension of {@code format}. */
  public static String fileName(String apiFileName, SchematicFormat format) {
    return baseName(apiFileName, format) + format.extension();
  }

  /**
   * The first of {@code fileName}, {@code name (2).ext}, {@code name (3).ext}... that {@code taken}
   * rejects; {@code fileName} must come from {@link #fileName}.
   *
   * @throws IllegalStateException when every suffix up to 9999 is taken
   */
  public static String unique(String fileName, Predicate<String> taken) {
    if (!taken.test(fileName)) {
      return fileName;
    }
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    String extension = dot > 0 ? fileName.substring(dot) : "";
    for (int suffix = 2; suffix <= MAX_COLLISION_SUFFIX; suffix++) {
      String candidate = base + " (" + suffix + ")" + extension;
      if (!taken.test(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no free name for " + fileName);
  }

  /** The safe base name: the API name without its format's extension, filtered and trimmed. */
  static String baseName(String apiFileName, SchematicFormat format) {
    String name = apiFileName;
    if (name.toLowerCase(Locale.ROOT).endsWith(format.extension())) {
      name = name.substring(0, name.length() - format.extension().length());
    }
    StringBuilder safe = new StringBuilder(Math.min(name.length(), MAX_BASE_LENGTH));
    int kept = 0;
    for (int offset = 0; offset < name.length() && kept < MAX_BASE_LENGTH; ) {
      int codePoint = name.codePointAt(offset);
      offset += Character.charCount(codePoint);
      int safeCodePoint = isAllowed(codePoint) ? codePoint : REPLACEMENT;
      // Runs of replaced characters and spaces collapse into one.
      if (isCollapsible(safeCodePoint)
          && !safe.isEmpty()
          && safe.charAt(safe.length() - 1) == safeCodePoint) {
        continue;
      }
      safe.appendCodePoint(safeCodePoint);
      kept++;
    }
    String trimmed = trimEdges(safe.toString());
    if (trimmed.isEmpty()) {
      return FALLBACK_BASE;
    }
    if (RESERVED_WINDOWS_NAMES.contains(reservedStem(trimmed))) {
      return REPLACEMENT + trimmed;
    }
    return trimmed;
  }

  private static boolean isAllowed(int codePoint) {
    return Character.isLetterOrDigit(codePoint) || ALLOWED_PUNCTUATION.indexOf(codePoint) >= 0;
  }

  private static boolean isCollapsible(int codePoint) {
    return codePoint == REPLACEMENT || codePoint == ' ';
  }

  /** Without leading or trailing dots, spaces and underscores (hidden files, Windows quirks). */
  private static String trimEdges(String name) {
    int start = 0;
    int end = name.length();
    while (start < end && isEdgeTrimmed(name.charAt(start))) {
      start++;
    }
    while (end > start && isEdgeTrimmed(name.charAt(end - 1))) {
      end--;
    }
    return name.substring(start, end);
  }

  private static boolean isEdgeTrimmed(char character) {
    return character == '.' || character == ' ' || character == REPLACEMENT;
  }

  /** Windows reserves a device name with any extension too ({@code con.txt}). */
  private static String reservedStem(String name) {
    int dot = name.indexOf('.');
    return (dot >= 0 ? name.substring(0, dot) : name).trim().toLowerCase(Locale.ROOT);
  }
}
