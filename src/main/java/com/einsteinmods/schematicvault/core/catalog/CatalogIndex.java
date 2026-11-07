package com.einsteinmods.schematicvault.core.catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Search, category, fine filters and sort over the whole catalog. Everything a query compares is
 * prepared once when the index is built (off the render thread): each design's shown and original
 * title, authors and tags (ids and shown names) as one normalized text, its difficulty, "verified"
 * mark, size and width buckets as bits of one {@code int}, and the designs in each sort order. A
 * query is then one pass over the designs in the chosen order: bit tests first (cheapest), then
 * plain {@code String} checks, no sorting.
 *
 * <p>Each design's {@link DesignScore} is computed once here too, as its place in one best-first
 * order ({@link #featured} compares two {@code int}s per design).
 */
public final class CatalogIndex {
  private static final char WORD_SEPARATOR = ' ';
  static final char PATH_SEPARATOR = '/';

  /** Cards in the featured row: one per difficulty. */
  public static final int FEATURED_COUNT = Difficulty.values().length;

  private static final int NONE = -1;

  // Bits of a design's traits: one per difficulty, then "verified", one per size and width bucket.
  private static final int DIFFICULTY_SHIFT = 0;
  private static final int VERIFIED_BIT = 1 << Difficulty.values().length;
  private static final int SIZE_SHIFT = Difficulty.values().length + 1;
  private static final int WIDTH_SHIFT = SIZE_SHIFT + SizeBucket.values().length;

  private final List<SchematicSummary> schematics;
  private final String[] titles;
  private final String[] searchTexts;
  private final String[] categories;
  private final int[] traits;
  private final int[] newestFirst;
  private final int[] smallestFirst;
  private final int[] scores;

  /** Each design's place in the best-first order: score, then newest, then id. */
  private final int[] ranks;

  private CatalogIndex(List<SchematicSummary> schematics, LocaleTexts locale) {
    this.schematics = List.copyOf(schematics);
    int count = this.schematics.size();
    titles = new String[count];
    searchTexts = new String[count];
    categories = new String[count];
    traits = new int[count];
    scores = new int[count];
    int newestDataVersion = DesignScore.newestDataVersion(this.schematics);
    for (int index = 0; index < count; index++) {
      SchematicSummary schematic = this.schematics.get(index);
      titles[index] = locale.title(schematic);
      searchTexts[index] = searchText(schematic, titles[index], locale);
      categories[index] = schematic.category();
      traits[index] = traits(schematic);
      scores[index] = DesignScore.of(schematic, newestDataVersion);
    }
    newestFirst =
        order(
            count,
            Comparator.comparing((Integer index) -> this.schematics.get(index).updatedAt())
                .reversed());
    smallestFirst =
        order(
            count,
            Comparator.comparingLong((Integer index) -> this.schematics.get(index).blockCount()));
    int[] bestFirst =
        order(
            count,
            Comparator.comparingInt((Integer index) -> -scores[index])
                .thenComparing(
                    (Integer index) -> this.schematics.get(index).updatedAt(),
                    Comparator.reverseOrder())
                .thenComparing((Integer index) -> this.schematics.get(index).id()));
    ranks = new int[count];
    for (int position = 0; position < count; position++) {
      ranks[bestFirst[position]] = position;
    }
  }

  /**
   * @param locale shown titles and tag names, searched next to the original ones
   */
  public static CatalogIndex of(List<SchematicSummary> schematics, LocaleTexts locale) {
    return new CatalogIndex(schematics, locale);
  }

  public int size() {
    return schematics.size();
  }

  public SchematicSummary get(int index) {
    return schematics.get(index);
  }

  /** Shown title of the design at {@code index}: its locale title, else the original one. */
  public String title(int index) {
    return titles[index];
  }

  /** {@link DesignScore} of the design at {@code index}. */
  public int score(int index) {
    return scores[index];
  }

  /**
   * The featured designs among {@code matches} (catalog indices, e.g. a {@link #filter} result):
   * the best of each difficulty, easy first; a difficulty without designs takes the best design
   * left (labelled with its own difficulty), never one already featured. Fewer than {@link
   * #FEATURED_COUNT} only when there are fewer matches. One pass over {@code matches}.
   */
  public int[] featured(int[] matches) {
    int[] bestOfDifficulty = new int[FEATURED_COUNT];
    Arrays.fill(bestOfDifficulty, NONE);
    // The best designs overall, best first: enough to fill every slot left empty.
    int[] bestOverall = new int[FEATURED_COUNT];
    Arrays.fill(bestOverall, NONE);
    for (int match : matches) {
      int rank = ranks[match];
      Difficulty difficulty = schematics.get(match).difficulty();
      if (difficulty != null) {
        int slot = difficulty.ordinal();
        if (bestOfDifficulty[slot] == NONE || rank < ranks[bestOfDifficulty[slot]]) {
          bestOfDifficulty[slot] = match;
        }
      }
      insertByRank(bestOverall, match, rank);
    }
    int[] featured = new int[FEATURED_COUNT];
    int found = 0;
    for (int slot = 0; slot < FEATURED_COUNT; slot++) {
      int pick = bestOfDifficulty[slot];
      if (pick == NONE) {
        pick = firstNotIn(bestOverall, bestOfDifficulty, featured, found);
      }
      if (pick != NONE) {
        featured[found++] = pick;
      }
    }
    return found == FEATURED_COUNT ? featured : Arrays.copyOf(featured, found);
  }

  /** Puts {@code match} into the best-first {@code best} if it ranks among them. */
  private void insertByRank(int[] best, int match, int rank) {
    int position = best.length;
    while (position > 0 && (best[position - 1] == NONE || rank < ranks[best[position - 1]])) {
      position--;
    }
    if (position == best.length) {
      return;
    }
    System.arraycopy(best, position, best, position + 1, best.length - position - 1);
    best[position] = match;
  }

  /** The first of {@code candidates} that is no slot's own pick and not featured yet. */
  private static int firstNotIn(int[] candidates, int[] slotPicks, int[] featured, int found) {
    for (int candidate : candidates) {
      if (candidate != NONE
          && !contains(slotPicks, slotPicks.length, candidate)
          && !contains(featured, found, candidate)) {
        return candidate;
      }
    }
    return NONE;
  }

  private static boolean contains(int[] values, int length, int value) {
    for (int position = 0; position < length; position++) {
      if (values[position] == value) {
        return true;
      }
    }
    return false;
  }

  /** Indices of the designs that match {@code filter}, in its order. */
  public int[] filter(CatalogFilter filter) {
    Query query = new Query(filter);
    SortOrder order = filter.sort();
    int count = schematics.size();
    int[] matches = new int[count];
    int found = 0;
    for (int position = 0; position < count; position++) {
      int index =
          switch (order) {
            case NEWEST -> newestFirst[position];
            case SMALLEST -> smallestFirst[position];
            case LARGEST -> smallestFirst[count - 1 - position];
          };
      if (matches(index, query)) {
        matches[found++] = index;
      }
    }
    return Arrays.copyOf(matches, found);
  }

  /**
   * As {@link #filter}, over {@code candidates} only: the result of an earlier filter of this index
   * that {@code filter} {@link CatalogFilter#refines refines}, so every match is among them and in
   * the same order. One pass over the candidates; they are not changed.
   */
  public int[] refine(CatalogFilter filter, int[] candidates) {
    Query query = new Query(filter);
    int[] matches = new int[candidates.length];
    int found = 0;
    for (int index : candidates) {
      if (matches(index, query)) {
        matches[found++] = index;
      }
    }
    return Arrays.copyOf(matches, found);
  }

  /** Bit tests first (cheapest), then the category, then the search terms. */
  private boolean matches(int index, Query query) {
    int designTraits = traits[index];
    return matchesAny(designTraits, query.difficultyMask)
        && matchesAny(designTraits, query.verifiedMask)
        && matchesAny(designTraits, query.sizeMask)
        && matchesAny(designTraits, query.widthMask)
        && inCategory(categories[index], query.categoryPath)
        && containsAll(searchTexts[index], query.terms);
  }

  /** A filter as one pass compares it: normalized terms and trait masks. */
  private static final class Query {
    private final String[] terms;
    private final String categoryPath;
    private final int difficultyMask;
    private final int verifiedMask;
    private final int sizeMask;
    private final int widthMask;

    private Query(CatalogFilter filter) {
      terms = SearchText.terms(filter.text()).toArray(String[]::new);
      categoryPath = filter.categoryPath();
      int difficulties = 0;
      for (Difficulty difficulty : filter.difficulties()) {
        difficulties |= difficultyBit(difficulty);
      }
      int sizes = 0;
      for (SizeBucket size : filter.sizes()) {
        sizes |= sizeBit(size);
      }
      int widths = 0;
      for (WidthBucket width : filter.widths()) {
        widths |= widthBit(width);
      }
      difficultyMask = difficulties;
      verifiedMask = filter.verifiedOnly() ? VERIFIED_BIT : 0;
      sizeMask = sizes;
      widthMask = widths;
    }
  }

  /** A filter that is off (no bit chosen) matches every design; else one shared bit is enough. */
  private static boolean matchesAny(int designTraits, int mask) {
    return mask == 0 || (designTraits & mask) != 0;
  }

  private static int traits(SchematicSummary schematic) {
    Difficulty difficulty = schematic.difficulty();
    int bits = difficulty == null ? 0 : difficultyBit(difficulty);
    if (schematic.verified()) {
      bits |= VERIFIED_BIT;
    }
    return bits
        | sizeBit(SizeBucket.of(schematic.blockCount()))
        | widthBit(WidthBucket.of(schematic.width()));
  }

  private static int difficultyBit(Difficulty difficulty) {
    return 1 << (DIFFICULTY_SHIFT + difficulty.ordinal());
  }

  private static int sizeBit(SizeBucket size) {
    return 1 << (SIZE_SHIFT + size.ordinal());
  }

  private static int widthBit(WidthBucket width) {
    return 1 << (WIDTH_SHIFT + width.ordinal());
  }

  /** Whether {@code category} is {@code path} or one of its children. */
  static boolean inCategory(String category, String path) {
    if (path.isEmpty()) {
      return true;
    }
    return category.startsWith(path)
        && (category.length() == path.length() || category.charAt(path.length()) == PATH_SEPARATOR);
  }

  private static boolean containsAll(String text, String[] terms) {
    for (String term : terms) {
      if (!text.contains(term)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Shown and original title, authors, and tags (ids and shown names) as one normalized text of
   * distinct words ({@link SearchText#distinctWords}, a title and its translation share many);
   * terms never hold spaces, so none spans two.
   */
  private static String searchText(
      SchematicSummary schematic, String shownTitle, LocaleTexts locale) {
    StringBuilder text = new StringBuilder(shownTitle);
    if (!shownTitle.equals(schematic.title())) {
      text.append(WORD_SEPARATOR).append(schematic.title());
    }
    for (String author : schematic.authors()) {
      text.append(WORD_SEPARATOR).append(author);
    }
    for (String tag : schematic.tags()) {
      // Tags are slugs ("slime-blocks"): each of their words is inside the slug, so it matches.
      text.append(WORD_SEPARATOR).append(tag);
      text.append(WORD_SEPARATOR).append(locale.tagName(tag));
    }
    return SearchText.distinctWords(SearchText.normalize(text.toString()));
  }

  /** Indices 0..count-1 sorted by {@code comparator}; ties keep the catalog's order. */
  private static int[] order(int count, Comparator<Integer> comparator) {
    Integer[] indices = new Integer[count];
    for (int index = 0; index < count; index++) {
      indices[index] = index;
    }
    Arrays.sort(indices, comparator);
    int[] sorted = new int[count];
    for (int index = 0; index < count; index++) {
      sorted[index] = indices[index];
    }
    return sorted;
  }
}
