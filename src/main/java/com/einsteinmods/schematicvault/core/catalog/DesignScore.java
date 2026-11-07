package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;

/**
 * A design's automatic score for the featured row ({@link CatalogIndex#featured}): the sum of the
 * points below, from what the catalog pages give; higher is better. Computed once per design when
 * the index is built.
 *
 * <ul>
 *   <li>{@link #VERIFIED}: the source marks it tested and working.
 *   <li>Review state: {@link #REVIEWED}; {@link #REJECTED} and {@link #OUTDATED} sink it below any
 *       design that is not, so it is featured only when nothing else is left.
 *   <li>Up to {@link #RECENT}, falling linearly to 0 over {@link #RECENCY_WINDOW} data versions
 *       behind the catalog's newest: designs for the current game first.
 *   <li>{@link #DESCRIBED} for a description, {@link #THUMBNAIL} for an image: what the card and
 *       the detail panel can show.
 *   <li>Up to {@link #POPULARITY} for the API's optional {@code popularity} (0-100, linear): as
 *       much as being verified and reviewed together; designs it does not rate get none.
 *   <li>The source's own state (optional): {@link #CURATED_SOURCE} when curated or accepted, {@link
 *       #EXPERIMENTAL_SOURCE} when experimental, {@link #DROPPED_SOURCE} when rejected or
 *       deprecated.
 *   <li>{@link #ENDORSEMENT} per endorsement (optional), counting {@link #MAX_ENDORSEMENTS} at
 *       most, so a few marks of trust help without outweighing the rest.
 *   <li>{@link #STATED_RATES} when its throughput is stated (optional).
 * </ul>
 *
 * <p>A field the API does not give scores 0, as a neutral value: these only come from the pages, so
 * the score never costs a request.
 */
public final class DesignScore {
  static final int VERIFIED = 30;
  static final int REVIEWED = 20;

  static final int RECENT = 15;

  /** About four years of releases: 1.19 (3105) is 1,918 behind the newest design today (5023). */
  static final int RECENCY_WINDOW = 2000;

  static final int DESCRIBED = 5;
  static final int THUMBNAIL = 5;
  static final int POPULARITY = 50;
  static final int CURATED_SOURCE = 15;
  static final int EXPERIMENTAL_SOURCE = -10;
  static final int DROPPED_SOURCE = -30;
  static final int ENDORSEMENT = 2;
  static final int MAX_ENDORSEMENTS = 5;
  static final int STATED_RATES = 10;

  /**
   * The most a design can earn with any review state but {@link #REVIEWED}: derived, so {@link
   * #REJECTED} and {@link #OUTDATED} stay beyond it when a weight changes.
   */
  static final int MAX_EARNED =
      VERIFIED
          + RECENT
          + DESCRIBED
          + THUMBNAIL
          + POPULARITY
          + CURATED_SOURCE
          + ENDORSEMENT * MAX_ENDORSEMENTS
          + STATED_RATES;

  static final int REJECTED = -MAX_EARNED - 20;
  static final int OUTDATED = -MAX_EARNED - 10;

  private DesignScore() {}

  /**
   * @param newestDataVersion the newest data version in the catalog ({@link #newestDataVersion})
   */
  public static int of(SchematicSummary schematic, int newestDataVersion) {
    int score = schematic.verified() ? VERIFIED : 0;
    ReviewStatus status = schematic.status();
    if (status != null) {
      score +=
          switch (status) {
            case REVIEWED -> REVIEWED;
            case UNREVIEWED -> 0;
            case REJECTED -> REJECTED;
            case OUTDATED -> OUTDATED;
          };
    }
    score += recency(schematic.dataVersion(), newestDataVersion);
    if (schematic.described()) {
      score += DESCRIBED;
    }
    if (schematic.hasThumbnail()) {
      score += THUMBNAIL;
    }
    int popularity = schematic.popularity();
    if (popularity != SchematicSummary.NO_POPULARITY) {
      score += POPULARITY * popularity / SchematicSummary.MAX_POPULARITY;
    }
    SourceStatus sourceStatus = schematic.sourceStatus();
    if (sourceStatus != null) {
      score +=
          switch (sourceStatus) {
            case CURATED, ACCEPTED -> CURATED_SOURCE;
            case EXPERIMENTAL -> EXPERIMENTAL_SOURCE;
            case REJECTED, DEPRECATED -> DROPPED_SOURCE;
            case SUBMITTED, LEGACY, COMMUNITY, CHALLENGE -> 0;
          };
    }
    score += ENDORSEMENT * Math.min(schematic.endorsementCount(), MAX_ENDORSEMENTS);
    if (schematic.hasRates()) {
      score += STATED_RATES;
    }
    return score;
  }

  /** The highest known data version of {@code schematics}; 0 when none is known. */
  public static int newestDataVersion(List<SchematicSummary> schematics) {
    int newest = 0;
    for (SchematicSummary schematic : schematics) {
      newest = Math.max(newest, schematic.dataVersion());
    }
    return newest;
  }

  private static int recency(int dataVersion, int newestDataVersion) {
    if (dataVersion <= 0) {
      return 0;
    }
    int behind = Math.max(0, newestDataVersion - dataVersion);
    return behind >= RECENCY_WINDOW ? 0 : RECENT * (RECENCY_WINDOW - behind) / RECENCY_WINDOW;
  }
}
