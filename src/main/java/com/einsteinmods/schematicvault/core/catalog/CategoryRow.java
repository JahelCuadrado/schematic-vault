package com.einsteinmods.schematicvault.core.catalog;

/**
 * One line of the category picker.
 *
 * @param path filter path; empty for "All"
 * @param name shown name (locale or English); empty for "All", which the screen names
 * @param depth 0 for "All" and the top categories, one more per level below
 */
public record CategoryRow(String path, String name, int depth, int count) {
  /** The "every category" row. */
  public static CategoryRow all(int count) {
    return new CategoryRow("", "", 0, count);
  }

  public boolean isAll() {
    return path.isEmpty();
  }
}
