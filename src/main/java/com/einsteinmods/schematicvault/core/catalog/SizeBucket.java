package com.einsteinmods.schematicvault.core.catalog;

/**
 * Size of a design by its block count. The bounds are round numbers next to the real catalog's
 * quartiles (269 / 2,354 / 26,964 blocks over 6,312 designs, API v1.2.0), so each bucket holds
 * about a quarter of it: 1,666 / 1,645 / 1,493 / 1,508. A design without a count (0) is small.
 */
public enum SizeBucket {
  /** Fewer than 300 blocks. */
  SMALL(0),
  /** 300 to 2,999 blocks. */
  MEDIUM(300),
  /** 3,000 to 29,999 blocks. */
  LARGE(3_000),
  /** 30,000 blocks or more. */
  HUGE(30_000);

  private static final SizeBucket[] ALL = values();

  private final long minBlocks;

  SizeBucket(long minBlocks) {
    this.minBlocks = minBlocks;
  }

  /** Fewest blocks a design of this bucket has. */
  public long minBlocks() {
    return minBlocks;
  }

  /** The block count where the next bucket starts; {@link Long#MAX_VALUE} for the last one. */
  public long maxBlocksExclusive() {
    return ordinal() + 1 < ALL.length ? ALL[ordinal() + 1].minBlocks : Long.MAX_VALUE;
  }

  public static SizeBucket of(long blockCount) {
    for (int index = ALL.length - 1; index > 0; index--) {
      if (blockCount >= ALL[index].minBlocks) {
        return ALL[index];
      }
    }
    return SMALL;
  }
}
