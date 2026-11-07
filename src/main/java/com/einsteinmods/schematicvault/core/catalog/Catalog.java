package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;

/**
 * The whole catalog as cached on disk: every design, the category tree and the texts of one API
 * language.
 *
 * @param revision the manifest revision the data came from
 * @param generatedAt ISO-8601 instant the API built this revision
 * @param locale shown titles, tag and category names
 */
public record Catalog(
    String revision,
    String generatedAt,
    List<SchematicSummary> schematics,
    List<CategoryNode> taxonomy,
    LocaleTexts locale) {
  public Catalog {
    schematics = List.copyOf(schematics);
    taxonomy = List.copyOf(taxonomy);
  }

  /** API language of {@link #locale} (e.g. {@code es}, {@code zh-CN}). */
  public String language() {
    return locale.language();
  }

  /** The same designs and tree with the texts of another language. */
  public Catalog withLocale(LocaleTexts newLocale) {
    return new Catalog(revision, generatedAt, schematics, taxonomy, newLocale);
  }

  /**
   * Whether {@code path} is a category without subcategories; false for "All" and unknown paths.
   */
  public boolean isLeafCategory(String path) {
    return isLeaf(taxonomy, path);
  }

  private static boolean isLeaf(List<CategoryNode> nodes, String path) {
    for (CategoryNode node : nodes) {
      if (node.path().equals(path)) {
        return node.children().isEmpty();
      }
      if (isLeaf(node.children(), path)) {
        return true;
      }
    }
    return false;
  }

  /** Shown name of a category: its locale name, else the English one. */
  public String categoryName(CategoryNode node) {
    return locale.categoryName(node);
  }
}
