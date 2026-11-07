package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LayerRangeTest {
  @Test
  void endsMayMeetButNotCross() {
    assertTrue(new LayerRange(7, 7).isSingleLayer());
    assertFalse(new LayerRange(7, 8).isSingleLayer());
    assertThrows(IllegalArgumentException.class, () -> new LayerRange(8, 7));
    assertThrows(IllegalArgumentException.class, () -> new LayerRange(-1, 7));
  }

  @Test
  void coversOnlyEveryLayer() {
    assertTrue(new LayerRange(0, 39).covers(40));
    assertTrue(new LayerRange(0, 99).covers(40));
    assertTrue(LayerRange.ALL.covers(40));
    assertFalse(new LayerRange(0, 38).covers(40));
    assertFalse(new LayerRange(1, 39).covers(40));
  }

  @Test
  void clampingKeepsTheRangeInsideTheDesign() {
    LayerRange inside = new LayerRange(2, 5);
    assertSame(inside, inside.clampedTo(6), "nothing allocated when it fits");
    assertEquals(new LayerRange(0, 39), LayerRange.ALL.clampedTo(40));
    assertEquals(new LayerRange(2, 3), inside.clampedTo(4));
    assertEquals(new LayerRange(1, 1), inside.clampedTo(2), "above the design: its top layer");
    assertEquals(new LayerRange(0, 0), inside.clampedTo(0), "no design yet");
  }

  @Test
  void containsAndOverlapsCountBothEnds() {
    LayerRange range = new LayerRange(2, 4);

    assertTrue(range.contains(2));
    assertTrue(range.contains(4));
    assertFalse(range.contains(5));
    assertTrue(range.contains(new LayerRange(3, 4)));
    assertFalse(range.contains(new LayerRange(3, 5)));
    assertTrue(range.overlaps(new LayerRange(4, 9)));
    assertTrue(range.overlaps(new LayerRange(0, 2)));
    assertFalse(range.overlaps(new LayerRange(5, 9)));
  }
}
