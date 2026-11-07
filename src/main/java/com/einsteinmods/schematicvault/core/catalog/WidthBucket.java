package com.einsteinmods.schematicvault.core.catalog;

/**
 * A farm's width (page item {@code width}) in the API's own buckets ({@code v1/facets/width.json}):
 * 1, 2, 3, 4-5, 6-10 and 11+. A width of 0 (unknown) counts as 1, as the API counts it.
 */
public enum WidthBucket {
  ONE(1, "1"),
  TWO(2, "2"),
  THREE(3, "3"),
  FOUR_TO_FIVE(5, "4-5"),
  SIX_TO_TEN(10, "6-10"),
  ELEVEN_OR_MORE(Integer.MAX_VALUE, "11+");

  private static final WidthBucket[] ALL = values();

  private final int maxWidth;
  private final String label;

  WidthBucket(int maxWidth, String label) {
    this.maxWidth = maxWidth;
    this.label = label;
  }

  /** The API's facet value ({@code 4-5}), shown as is in every language. */
  public String label() {
    return label;
  }

  public static WidthBucket of(int width) {
    for (WidthBucket bucket : ALL) {
      if (width <= bucket.maxWidth) {
        return bucket;
      }
    }
    return ELEVEN_OR_MORE;
  }
}
