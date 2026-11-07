package com.einsteinmods.schematicvault.core.thumbnails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThumbnailPlanTest {
  private static final long MEGABYTE = 1024 * 1024;

  @Test
  void filesOverTheSizeRuleAreLeftOutUnread() {
    ThumbnailPlan plan = ThumbnailPlan.of(new long[] {100, ThumbnailPlan.MAX_FILE_BYTES + 1, 200});
    assertEquals(new ThumbnailPlan(2, 300, 1), plan);
  }

  @Test
  void aFileExactlyAtTheRuleIsRead() {
    assertFalse(ThumbnailPlan.tooBig(ThumbnailPlan.MAX_FILE_BYTES));
    assertTrue(ThumbnailPlan.tooBig(ThumbnailPlan.MAX_FILE_BYTES + 1));
  }

  @Test
  void nothingToDoTakesNoTime() {
    assertEquals(0, ThumbnailPlan.of(new long[0]).estimatedSeconds());
  }

  @Test
  void shortEstimatesRoundUpToFiveSeconds() {
    assertEquals(5, new ThumbnailPlan(1, 0, 0).estimatedSeconds());
    assertEquals(35, new ThumbnailPlan(200, 5 * MEGABYTE, 0).estimatedSeconds());
  }

  @Test
  void longEstimatesRoundUpToMinutes() {
    assertEquals(120, new ThumbnailPlan(500, 40 * MEGABYTE, 0).estimatedSeconds());
    assertEquals(60, new ThumbnailPlan(0, 60 * MEGABYTE, 0).estimatedSeconds());
  }

  @Test
  void largerFilesTakeLonger() {
    assertTrue(
        new ThumbnailPlan(10, 100 * MEGABYTE, 0).estimatedSeconds()
            > new ThumbnailPlan(10, 0, 0).estimatedSeconds());
  }
}
