package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DesignScoreTest {
  private static final int NEWEST = 5000;

  static SchematicSummary scored(
      String id,
      @Nullable Difficulty difficulty,
      boolean verified,
      @Nullable ReviewStatus status,
      int dataVersion,
      boolean described,
      @Nullable String thumbnail,
      int popularity,
      String category,
      String updatedAt) {
    return withSourceSignals(
        id,
        difficulty,
        verified,
        status,
        dataVersion,
        described,
        thumbnail,
        popularity,
        category,
        updatedAt,
        null,
        0,
        false);
  }

  private static SchematicSummary withSourceSignals(
      String id,
      @Nullable Difficulty difficulty,
      boolean verified,
      @Nullable ReviewStatus status,
      int dataVersion,
      boolean described,
      @Nullable String thumbnail,
      int popularity,
      String category,
      String updatedAt,
      @Nullable SourceStatus sourceStatus,
      int endorsementCount,
      boolean hasRates) {
    return new SchematicSummary(
        id,
        id,
        List.of(),
        List.of(),
        category,
        null,
        "source",
        null,
        BlockSize.UNKNOWN,
        0,
        difficulty,
        verified,
        0,
        null,
        thumbnail,
        updatedAt,
        status,
        dataVersion,
        described,
        popularity,
        sourceStatus,
        endorsementCount,
        hasRates);
  }

  private static int sourceScore(
      @Nullable SourceStatus sourceStatus, int endorsementCount, boolean hasRates) {
    return DesignScore.of(
        withSourceSignals(
            "x",
            Difficulty.EASY,
            false,
            null,
            0,
            false,
            null,
            SchematicSummary.NO_POPULARITY,
            "farms",
            "",
            sourceStatus,
            endorsementCount,
            hasRates),
        NEWEST);
  }

  private static int score(
      boolean verified,
      @Nullable ReviewStatus status,
      int dataVersion,
      boolean described,
      @Nullable String thumbnail,
      int popularity) {
    return DesignScore.of(
        scored(
            "x",
            Difficulty.EASY,
            verified,
            status,
            dataVersion,
            described,
            thumbnail,
            popularity,
            "farms",
            ""),
        NEWEST);
  }

  @Test
  void bareDesignScoresZero() {
    assertEquals(0, score(false, null, 0, false, null, SchematicSummary.NO_POPULARITY));
    assertEquals(
        0, score(false, ReviewStatus.UNREVIEWED, 0, false, null, SchematicSummary.NO_POPULARITY));
  }

  @Test
  void eachSignalAddsItsWeight() {
    int none = SchematicSummary.NO_POPULARITY;
    assertEquals(DesignScore.VERIFIED, score(true, null, 0, false, null, none));
    assertEquals(DesignScore.REVIEWED, score(false, ReviewStatus.REVIEWED, 0, false, null, none));
    assertEquals(DesignScore.DESCRIBED, score(false, null, 0, true, null, none));
    assertEquals(DesignScore.THUMBNAIL, score(false, null, 0, false, "v1/images/a.png", none));
    assertEquals(DesignScore.RECENT, score(false, null, NEWEST, false, null, none));
    assertEquals(DesignScore.POPULARITY, score(false, null, 0, false, null, 100));
    assertEquals(DesignScore.POPULARITY / 2, score(false, null, 0, false, null, 50));
    assertEquals(0, score(false, null, 0, false, null, 0));
    assertEquals(
        DesignScore.VERIFIED
            + DesignScore.REVIEWED
            + DesignScore.RECENT
            + DesignScore.DESCRIBED
            + DesignScore.THUMBNAIL,
        score(true, ReviewStatus.REVIEWED, NEWEST, true, "v1/images/a.png", none));
  }

  @Test
  void recencyFallsLinearlyOverTheWindow() {
    int none = SchematicSummary.NO_POPULARITY;
    int half = NEWEST - DesignScore.RECENCY_WINDOW / 2;
    assertEquals(DesignScore.RECENT / 2, score(false, null, half, false, null, none));
    int old = NEWEST - DesignScore.RECENCY_WINDOW;
    assertEquals(0, score(false, null, old, false, null, none));
    assertEquals(0, score(false, null, 1, false, null, none));
  }

  @Test
  void sourceSignalsAreNeutralWhenAbsent() {
    assertEquals(0, sourceScore(null, 0, false));
  }

  @Test
  void sourceStatusGivesItsWeight() {
    assertEquals(DesignScore.CURATED_SOURCE, sourceScore(SourceStatus.CURATED, 0, false));
    assertEquals(DesignScore.CURATED_SOURCE, sourceScore(SourceStatus.ACCEPTED, 0, false));
    assertEquals(DesignScore.EXPERIMENTAL_SOURCE, sourceScore(SourceStatus.EXPERIMENTAL, 0, false));
    assertEquals(DesignScore.DROPPED_SOURCE, sourceScore(SourceStatus.REJECTED, 0, false));
    assertEquals(DesignScore.DROPPED_SOURCE, sourceScore(SourceStatus.DEPRECATED, 0, false));
    for (SourceStatus neutral :
        List.of(
            SourceStatus.COMMUNITY,
            SourceStatus.LEGACY,
            SourceStatus.SUBMITTED,
            SourceStatus.CHALLENGE)) {
      assertEquals(0, sourceScore(neutral, 0, false), neutral.id());
    }
  }

  @Test
  void endorsementsAreCappedAndRatesAddTheirWeight() {
    assertEquals(DesignScore.ENDORSEMENT * 2, sourceScore(null, 2, false));
    int capped = DesignScore.ENDORSEMENT * DesignScore.MAX_ENDORSEMENTS;
    assertEquals(capped, sourceScore(null, DesignScore.MAX_ENDORSEMENTS, false));
    assertEquals(capped, sourceScore(null, 500, false));
    assertEquals(DesignScore.STATED_RATES, sourceScore(null, 0, true));
  }

  /** Every signal at its best, so a new weight left out of MAX_EARNED fails here. */
  @Test
  void rejectedAndOutdatedSinkBelowABareDesign() {
    for (ReviewStatus status : List.of(ReviewStatus.REJECTED, ReviewStatus.OUTDATED)) {
      SchematicSummary best =
          withSourceSignals(
              "x",
              Difficulty.EASY,
              true,
              status,
              NEWEST,
              true,
              "v1/images/a.png",
              SchematicSummary.MAX_POPULARITY,
              "farms",
              "",
              SourceStatus.CURATED,
              500,
              true);
      assertTrue(DesignScore.of(best, NEWEST) < 0, status.id());
    }
  }

  @Test
  void newestDataVersionIgnoresUnknown() {
    assertEquals(
        4671,
        DesignScore.newestDataVersion(
            List.of(
                scored("a", null, false, null, 4671, false, null, -1, "", ""),
                scored("b", null, false, null, 0, false, null, -1, "", ""),
                scored("c", null, false, null, 3105, false, null, -1, "", ""))));
    assertEquals(0, DesignScore.newestDataVersion(List.of()));
  }
}
