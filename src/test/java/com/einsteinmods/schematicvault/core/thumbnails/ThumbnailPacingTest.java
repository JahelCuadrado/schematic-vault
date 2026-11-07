package com.einsteinmods.schematicvault.core.thumbnails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThumbnailPacingTest {
  private static final long FRAME_NANOS = 16_666_667;
  private static final long MILLISECOND = 1_000_000;

  /** Frames until {@code pacing} allows an image, from {@code start}. */
  private static int framesUntilReady(ThumbnailPacing pacing, long start) {
    int frames = 1;
    while (!pacing.ready(start + frames * FRAME_NANOS)) {
      frames++;
    }
    return frames;
  }

  @Test
  void theFirstImageWaitsForTheFramesToo() {
    assertEquals(ThumbnailPacing.MIN_FRAMES, framesUntilReady(new ThumbnailPacing(), 0));
  }

  @Test
  void aCheapImageWaitsOnlyForTheFrames() {
    ThumbnailPacing pacing = new ThumbnailPacing();
    pacing.drawn(MILLISECOND / 2, 0);
    assertEquals(ThumbnailPacing.MIN_FRAMES, framesUntilReady(pacing, 0));
  }

  @Test
  void anExpensiveImageBuysARestSoItsShareStaysUnderTheBudget() {
    long cost = 20 * MILLISECOND;
    ThumbnailPacing pacing = new ThumbnailPacing();
    pacing.drawn(cost, 0);
    long elapsed = framesUntilReady(pacing, 0) * FRAME_NANOS;
    assertTrue(cost * ThumbnailPacing.MAX_SHARE <= elapsed + cost);
  }

  @Test
  void theRestHasACeiling() {
    ThumbnailPacing pacing = new ThumbnailPacing();
    pacing.drawn(60_000 * MILLISECOND, 0);
    for (int frame = 0; frame < ThumbnailPacing.MIN_FRAMES; frame++) {
      assertFalse(pacing.ready(ThumbnailPacing.MAX_REST_NANOS - 1));
    }
    assertTrue(pacing.ready(ThumbnailPacing.MAX_REST_NANOS));
  }
}
