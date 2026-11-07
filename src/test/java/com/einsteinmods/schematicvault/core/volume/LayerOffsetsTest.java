package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LayerOffsetsTest {
  @Test
  void startsAreMonotonicAndSumToTheTotal() {
    LayerOffsets.Builder builder = LayerOffsets.builder(5);
    builder.enter(0, 0);
    builder.enter(1, 4);
    builder.enter(3, 10);
    LayerOffsets offsets = builder.finish(12);
    assertEquals(5, offsets.layers());
    assertEquals(12, offsets.total());
    int[] ends = new int[offsets.layers() + 1];
    for (int layer = 0; layer <= offsets.layers(); layer++) {
      ends[layer] = offsets.end(layer);
    }
    // Layer 2 is empty (starts where layer 3 does); layer 4 holds nothing either.
    assertArrayEquals(new int[] {0, 4, 10, 10, 12, 12}, ends);
    int sum = 0;
    for (int layer = 0; layer < offsets.layers(); layer++) {
      sum += offsets.start(layer + 1) - offsets.start(layer);
    }
    assertEquals(offsets.total(), sum);
  }

  @Test
  void endsAreClampedAndEmptyListsHaveNothing() {
    LayerOffsets.Builder builder = LayerOffsets.builder(3);
    builder.enter(2, 0);
    LayerOffsets offsets = builder.finish(7);
    assertEquals(0, offsets.end(0));
    assertEquals(0, offsets.end(-1));
    assertEquals(0, offsets.end(2));
    assertEquals(7, offsets.end(3));
    assertEquals(7, offsets.end(40));
    LayerOffsets empty = LayerOffsets.builder(4).finish(0);
    assertEquals(0, empty.end(4));
  }

  @Test
  void aRangeIsTheRunBetweenItsEnds() {
    LayerOffsets.Builder builder = LayerOffsets.builder(5);
    builder.enter(0, 0);
    builder.enter(1, 4);
    builder.enter(3, 10);
    LayerOffsets offsets = builder.finish(12);
    // Layers 1-3 hold items 4 to 11 (layer 2 is empty).
    assertEquals(4, offsets.start(1));
    assertEquals(12, offsets.end(3 + 1));
    // The empty layer alone: an empty run.
    assertEquals(offsets.start(2), offsets.end(2 + 1));
    // One layer, and the whole list.
    assertEquals(4, offsets.start(0 + 1) - offsets.start(0));
    assertEquals(offsets.total(), offsets.end(4 + 1) - offsets.start(0));
    // A range past the list's height clips to its end.
    assertEquals(12, offsets.end(40));
    // Every split into two ranges keeps every item.
    for (int split = 0; split <= offsets.layers(); split++) {
      assertEquals(
          offsets.total(),
          (offsets.end(split) - offsets.start(0))
              + (offsets.end(offsets.layers()) - offsets.start(split)));
    }
  }

  @Test
  void layersMustComeInOrder() {
    LayerOffsets.Builder builder = LayerOffsets.builder(4);
    builder.enter(2, 3);
    builder.enter(2, 5);
    assertThrows(IllegalArgumentException.class, () -> builder.enter(0, 6));
    assertThrows(IllegalArgumentException.class, () -> builder.enter(3, 1));
    assertThrows(IllegalArgumentException.class, () -> builder.enter(4, 9));
  }

  @Test
  void orderKeepsLayersTogetherFarthestFirstInEach() {
    LayerOffsets.Builder builder = LayerOffsets.builder(2);
    builder.enter(0, 0);
    builder.enter(1, 3);
    LayerOffsets offsets = builder.finish(5);
    float[] keys = {-1.0F, 5.0F, 2.0F, 0.5F, 9.0F};
    assertArrayEquals(new int[] {1, 2, 0, 4, 3}, offsets.orderWithinLayers(keys));
    assertThrows(IllegalArgumentException.class, () -> offsets.orderWithinLayers(new float[2]));
  }
}
