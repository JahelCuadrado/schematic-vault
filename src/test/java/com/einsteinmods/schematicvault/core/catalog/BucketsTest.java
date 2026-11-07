package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BucketsTest {
  @Test
  void sizeBucketBounds() {
    assertEquals(SizeBucket.SMALL, SizeBucket.of(0));
    assertEquals(SizeBucket.SMALL, SizeBucket.of(299));
    assertEquals(SizeBucket.MEDIUM, SizeBucket.of(300));
    assertEquals(SizeBucket.MEDIUM, SizeBucket.of(2_999));
    assertEquals(SizeBucket.LARGE, SizeBucket.of(3_000));
    assertEquals(SizeBucket.LARGE, SizeBucket.of(29_999));
    assertEquals(SizeBucket.HUGE, SizeBucket.of(30_000));
    assertEquals(SizeBucket.HUGE, SizeBucket.of(Long.MAX_VALUE));
    assertEquals(3_000, SizeBucket.MEDIUM.maxBlocksExclusive());
    assertEquals(Long.MAX_VALUE, SizeBucket.HUGE.maxBlocksExclusive());
  }

  @Test
  void widthBucketsAsTheApiCountsThem() {
    assertEquals(WidthBucket.ONE, WidthBucket.of(0));
    assertEquals(WidthBucket.ONE, WidthBucket.of(1));
    assertEquals(WidthBucket.TWO, WidthBucket.of(2));
    assertEquals(WidthBucket.THREE, WidthBucket.of(3));
    assertEquals(WidthBucket.FOUR_TO_FIVE, WidthBucket.of(4));
    assertEquals(WidthBucket.FOUR_TO_FIVE, WidthBucket.of(5));
    assertEquals(WidthBucket.SIX_TO_TEN, WidthBucket.of(6));
    assertEquals(WidthBucket.SIX_TO_TEN, WidthBucket.of(10));
    assertEquals(WidthBucket.ELEVEN_OR_MORE, WidthBucket.of(11));
    assertEquals(WidthBucket.ELEVEN_OR_MORE, WidthBucket.of(Integer.MAX_VALUE));
    assertEquals("4-5", WidthBucket.FOUR_TO_FIVE.label());
  }

  @Test
  void difficultyByApiValue() {
    assertEquals(Optional.of(Difficulty.HARD), Difficulty.fromId("hard"));
    assertEquals(Optional.empty(), Difficulty.fromId("Hard"));
    assertEquals(Optional.empty(), Difficulty.fromId(""));
  }
}
