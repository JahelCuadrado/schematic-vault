package com.einsteinmods.schematicvault.core.catalog;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the list shows: a category, a search text, the fine filters and the order. Immutable: every
 * change gives a new filter. An empty set or {@code false} means the filter is off; within one
 * filter any chosen value matches (easy or hard), across filters all must match.
 *
 * @param categoryPath designs in this category or below it; empty for every category
 * @param text words that must all appear in a title, an author or a tag; blank for any
 * @param verifiedOnly only designs the API marks as verified
 * @param widths farm widths; designs of any of these buckets
 */
public record CatalogFilter(
    String categoryPath,
    String text,
    Set<Difficulty> difficulties,
    boolean verifiedOnly,
    Set<SizeBucket> sizes,
    Set<WidthBucket> widths,
    SortOrder sort) {
  /** Every design, newest first. */
  public static final CatalogFilter NONE =
      new CatalogFilter("", "", Set.of(), false, Set.of(), Set.of(), SortOrder.NEWEST);

  public CatalogFilter {
    difficulties = Set.copyOf(difficulties);
    sizes = Set.copyOf(sizes);
    widths = Set.copyOf(widths);
  }

  /** Chosen fine-filter values (each difficulty, size and width, and "verified only"). */
  public int fineFilterCount() {
    return difficulties.size() + (verifiedOnly ? 1 : 0) + sizes.size() + widths.size();
  }

  /** Whether anything but the order narrows the list. */
  public boolean isActive() {
    return !categoryPath.isEmpty() || !text.isBlank() || fineFilterCount() > 0;
  }

  /**
   * Whether every design this filter matches also matches {@code previous}, in the same order, so
   * {@link CatalogIndex#refine} may search only {@code previous}'s matches: everything but the text
   * is equal and each of {@code previous}'s terms is inside one of this filter's terms (the text
   * typed on, or words added). A design's text holds every term of this filter, so it holds the
   * shorter ones inside them too.
   */
  public boolean refines(CatalogFilter previous) {
    if (!categoryPath.equals(previous.categoryPath)
        || verifiedOnly != previous.verifiedOnly
        || sort != previous.sort
        || !difficulties.equals(previous.difficulties)
        || !sizes.equals(previous.sizes)
        || !widths.equals(previous.widths)) {
      return false;
    }
    List<String> terms = SearchText.terms(text);
    for (String previousTerm : SearchText.terms(previous.text)) {
      if (!anyContains(terms, previousTerm)) {
        return false;
      }
    }
    return true;
  }

  private static boolean anyContains(List<String> terms, String part) {
    for (String term : terms) {
      if (term.contains(part)) {
        return true;
      }
    }
    return false;
  }

  public CatalogFilter withCategory(String path) {
    return new CatalogFilter(path, text, difficulties, verifiedOnly, sizes, widths, sort);
  }

  public CatalogFilter withText(String newText) {
    return new CatalogFilter(
        categoryPath, newText, difficulties, verifiedOnly, sizes, widths, sort);
  }

  public CatalogFilter withDifficulties(Set<Difficulty> values) {
    return new CatalogFilter(categoryPath, text, values, verifiedOnly, sizes, widths, sort);
  }

  public CatalogFilter withVerifiedOnly(boolean value) {
    return new CatalogFilter(categoryPath, text, difficulties, value, sizes, widths, sort);
  }

  public CatalogFilter withSizes(Set<SizeBucket> values) {
    return new CatalogFilter(categoryPath, text, difficulties, verifiedOnly, values, widths, sort);
  }

  public CatalogFilter withWidths(Set<WidthBucket> values) {
    return new CatalogFilter(categoryPath, text, difficulties, verifiedOnly, sizes, values, sort);
  }

  public CatalogFilter withSort(SortOrder order) {
    return new CatalogFilter(categoryPath, text, difficulties, verifiedOnly, sizes, widths, order);
  }

  /** Every filter off; the order is kept. */
  public CatalogFilter cleared() {
    return NONE.withSort(sort);
  }

  /** With {@code value} chosen if it was not, else without it. */
  public CatalogFilter toggled(Difficulty value) {
    return withDifficulties(toggled(difficulties, value, Difficulty.class));
  }

  public CatalogFilter toggled(SizeBucket value) {
    return withSizes(toggled(sizes, value, SizeBucket.class));
  }

  public CatalogFilter toggled(WidthBucket value) {
    return withWidths(toggled(widths, value, WidthBucket.class));
  }

  private static <E extends Enum<E>> Set<E> toggled(Set<E> values, E value, Class<E> type) {
    EnumSet<E> changed = EnumSet.noneOf(type);
    changed.addAll(values);
    if (!changed.remove(value)) {
      changed.add(value);
    }
    return changed;
  }
}
