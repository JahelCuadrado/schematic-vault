package com.einsteinmods.schematicvault.core.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TurnTableTest {
  private static final float EPSILON = 0.001F;

  @Test
  void theFirstCallOnlyStartsTheClock() {
    assertEquals(0.0F, new TurnTable().advance(5_000L));
  }

  @Test
  void aStepFollowsTheTimePassedAtAnyFrameRate() {
    float atTenFramesASecond = turnedInOneSecond(100L);
    float atHundredsOfFramesASecond = turnedInOneSecond(4L);

    assertEquals(TurnTable.DEGREES_PER_SECOND, atTenFramesASecond, EPSILON);
    assertEquals(atTenFramesASecond, atHundredsOfFramesASecond, 1.0F);
  }

  @Test
  void framesCloserThanAStepDoNotMoveTheView() {
    TurnTable table = new TurnTable();
    table.advance(0L);
    assertEquals(0.0F, table.advance(10L));
    assertEquals(0.0F, table.advance(20L));
    assertTrue(table.advance(40L) > 0.0F);
  }

  @Test
  void aPauseDoesNotMakeTheDesignJump() {
    TurnTable table = new TurnTable();
    table.advance(0L);
    assertEquals(TurnTable.DEGREES_PER_SECOND / 5.0F, table.advance(60_000L), EPSILON);
  }

  @Test
  void theBoxStaysInsideTheSpansThroughTheWholeTurn() {
    float sizeX = 100.0F;
    float sizeY = 7.0F;
    float sizeZ = 10.0F;
    float pitch = 30.0F;
    float widthSpan = TurnTable.widthSpan(sizeX, sizeZ);
    float heightSpan = TurnTable.heightSpan(sizeX, sizeY, sizeZ, pitch);
    double sinPitch = Math.sin(Math.toRadians(pitch));
    double cosPitch = Math.cos(Math.toRadians(pitch));
    for (int degrees = 0; degrees < 360; degrees++) {
      double yaw = Math.toRadians(degrees);
      double wide = Math.abs(sizeX * Math.cos(yaw)) + Math.abs(sizeZ * Math.sin(yaw));
      double deep = Math.abs(sizeX * Math.sin(yaw)) + Math.abs(sizeZ * Math.cos(yaw));
      assertTrue(wide <= widthSpan + EPSILON);
      assertTrue(deep * sinPitch + sizeY * cosPitch <= heightSpan + EPSILON);
    }
  }

  private static float turnedInOneSecond(long millisPerFrame) {
    TurnTable table = new TurnTable();
    table.advance(0L);
    float turned = 0.0F;
    for (long now = millisPerFrame; now <= 1_000L; now += millisPerFrame) {
      turned += table.advance(now);
    }
    return turned;
  }
}
