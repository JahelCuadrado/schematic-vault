package com.einsteinmods.schematicvault.core.thumbnails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThumbnailKeyTest {
  private static final String PATH = "/game/schematics/farm.litematic";
  private static final long SIZE = 1234;
  private static final long TIME = 1_000_000;

  @Test
  void theSameFileKeepsItsKey() {
    assertEquals(ThumbnailKey.of(PATH, SIZE, TIME), ThumbnailKey.of(PATH, SIZE, TIME));
  }

  @Test
  void aChangedFileGetsAnotherKey() {
    String key = ThumbnailKey.of(PATH, SIZE, TIME);
    assertNotEquals(key, ThumbnailKey.of(PATH, SIZE + 1, TIME));
    assertNotEquals(key, ThumbnailKey.of(PATH, SIZE, TIME + 1));
    assertNotEquals(key, ThumbnailKey.of("/game/schematics/other.litematic", SIZE, TIME));
  }

  @Test
  void numbersCannotMoveAcrossTheFields() {
    assertNotEquals(ThumbnailKey.of("a", 12, 3), ThumbnailKey.of("a", 1, 23));
  }

  @Test
  void anyPathMakesASafeFileName() {
    assertTrue(ThumbnailKey.of("C:\\a/b:c?*.nbt", SIZE, TIME).matches("[0-9a-f]{64}"));
  }
}
