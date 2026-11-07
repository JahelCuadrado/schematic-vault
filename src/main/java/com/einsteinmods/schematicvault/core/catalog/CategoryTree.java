package com.einsteinmods.schematicvault.core.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The category tree as picker rows: "All" first, then every node depth-first, API order. The picker
 * folds it: a row shows only while every category above it is expanded ({@link #visible}).
 */
public final class CategoryTree {
  private CategoryTree() {}

  public static List<CategoryRow> rows(Catalog catalog) {
    List<CategoryRow> rows = new ArrayList<>();
    rows.add(CategoryRow.all(catalog.schematics().size()));
    addRows(catalog, catalog.taxonomy(), 0, rows);
    return List.copyOf(rows);
  }

  /** The row of {@code path}, if the tree has it. */
  public static Optional<CategoryRow> find(List<CategoryRow> rows, String path) {
    for (CategoryRow row : rows) {
      if (row.path().equals(path)) {
        return Optional.of(row);
      }
    }
    return Optional.empty();
  }

  /**
   * The categories above {@code path}, top first: {@code farms/mob/iron} gives {@code farms} and
   * {@code farms/mob}; a top category and "All" (empty) give none.
   */
  public static List<String> ancestors(String path) {
    List<String> ancestors = new ArrayList<>();
    int end = path.indexOf(CatalogIndex.PATH_SEPARATOR);
    while (end > 0) {
      ancestors.add(path.substring(0, end));
      end = path.indexOf(CatalogIndex.PATH_SEPARATOR, end + 1);
    }
    return List.copyOf(ancestors);
  }

  /** Whether row {@code index} of {@link #rows} has children (the row after it is deeper). */
  public static boolean hasChildren(List<CategoryRow> rows, int index) {
    return !rows.get(index).isAll()
        && index + 1 < rows.size()
        && rows.get(index + 1).depth() > rows.get(index).depth();
  }

  /**
   * The indices of {@link #rows} shown when only the {@code expanded} paths are open, in order:
   * "All" and the top categories always, a deeper row only while every row above it is expanded.
   */
  public static int[] visible(List<CategoryRow> rows, Set<String> expanded) {
    int[] shown = new int[rows.size()];
    int count = 0;
    int hiddenBelow = Integer.MAX_VALUE;
    for (int index = 0; index < rows.size(); index++) {
      CategoryRow row = rows.get(index);
      if (row.depth() > hiddenBelow) {
        continue;
      }
      hiddenBelow = Integer.MAX_VALUE;
      shown[count++] = index;
      if (hasChildren(rows, index) && !expanded.contains(row.path())) {
        hiddenBelow = row.depth();
      }
    }
    return Arrays.copyOf(shown, count);
  }

  private static void addRows(
      Catalog catalog, List<CategoryNode> nodes, int depth, List<CategoryRow> rows) {
    for (CategoryNode node : nodes) {
      rows.add(new CategoryRow(node.path(), catalog.categoryName(node), depth, node.count()));
      addRows(catalog, node.children(), depth + 1, rows);
    }
  }
}
