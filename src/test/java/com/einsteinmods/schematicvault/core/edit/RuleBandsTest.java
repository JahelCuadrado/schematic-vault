package com.einsteinmods.schematicvault.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.volume.LayerRange;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBandsTest {
  private static final String OAK = "minecraft:oak_planks";
  private static final String SPRUCE = "minecraft:spruce_planks";
  private static final String STONE = "minecraft:stone";
  private static final int HEIGHT = 10;

  private static BlockRule rule(String source, String target, int bottom, int top) {
    return new BlockRule(source, target, new LayerRange(bottom, top));
  }

  private static BlockRule whole(String source, String target) {
    return new BlockRule(source, target, LayerRange.ALL);
  }

  private static String target(RuleBands bands, int y, String source) {
    BlockRule rule = bands.rule(bands.bandOfY()[y], source);
    return rule == null ? null : rule.targetBlock();
  }

  @Test
  void noRulesAreOneBandKeepingEveryBlock() {
    RuleBands bands = RuleBands.of(List.of(), HEIGHT);

    assertEquals(1, bands.count());
    assertTrue(bands.uniform());
    assertNull(bands.rule(0, OAK));
  }

  @Test
  void aWholeDesignRuleIsOneBand() {
    RuleBands bands = RuleBands.of(List.of(whole(OAK, SPRUCE)), HEIGHT);

    assertTrue(bands.uniform());
    assertEquals(SPRUCE, target(bands, 9, OAK));
  }

  @Test
  void aRangeSplitsTheLayersIntoThreeRunsOfTwoBands() {
    RuleBands bands = RuleBands.of(List.of(rule(OAK, SPRUCE, 2, 4)), HEIGHT);

    assertEquals(2, bands.count());
    assertFalse(bands.uniform());
    assertArrayEquals(new int[] {0, 0, 1, 1, 1, 0, 0, 0, 0, 0}, bands.bandOfY());
    assertNull(target(bands, 1, OAK));
    assertEquals(SPRUCE, target(bands, 2, OAK));
    assertEquals(SPRUCE, target(bands, 4, OAK));
    assertNull(target(bands, 5, OAK));
  }

  @Test
  void aRangeThatCoversEveryLayerIsUniform() {
    assertTrue(RuleBands.of(List.of(rule(OAK, SPRUCE, 0, 40)), HEIGHT).uniform());
    assertTrue(RuleBands.of(List.of(rule(OAK, SPRUCE, 0, HEIGHT - 1)), HEIGHT).uniform());
  }

  @Test
  void theLastRuleCoveringALayerWins() {
    RuleBands bands =
        RuleBands.of(List.of(rule(OAK, SPRUCE, 0, 6), rule(OAK, STONE, 4, 9)), HEIGHT);

    assertEquals(SPRUCE, target(bands, 3, OAK));
    assertEquals(STONE, target(bands, 4, OAK));
    assertEquals(STONE, target(bands, 6, OAK));
    assertEquals(STONE, target(bands, 9, OAK));
  }

  @Test
  void anEarlierWiderRuleShowsAroundALaterOne() {
    RuleBands bands = RuleBands.of(List.of(whole(OAK, SPRUCE), rule(OAK, STONE, 4, 5)), HEIGHT);

    assertEquals(SPRUCE, target(bands, 3, OAK));
    assertEquals(STONE, target(bands, 4, OAK));
    assertEquals(SPRUCE, target(bands, 6, OAK));
  }

  @Test
  void rulesAreOnlyAboutTheOriginalBlockNeverChained() {
    RuleBands bands = RuleBands.of(List.of(whole(OAK, SPRUCE), whole(SPRUCE, STONE)), HEIGHT);

    assertEquals(SPRUCE, target(bands, 0, OAK), "oak stops at spruce");
    assertEquals(STONE, target(bands, 0, SPRUCE));
  }

  @Test
  void layersWithTheSameWinnersShareABandAcrossAGap() {
    BlockRule first = rule(OAK, SPRUCE, 0, 1);
    BlockRule hole = rule(STONE, SPRUCE, 2, 3);
    RuleBands split = RuleBands.of(List.of(first, hole, rule(OAK, SPRUCE, 4, 5)), HEIGHT);
    RuleBands shared = RuleBands.of(List.of(whole(OAK, SPRUCE), hole), HEIGHT);

    assertNotEquals(split.bandOfY()[0], split.bandOfY()[4], "two rules: two rows to undo");
    assertEquals(shared.bandOfY()[0], shared.bandOfY()[4]);
    assertEquals(2, shared.count());
  }

  @Test
  void differentBlocksShareTheBreaksOfTheirRanges() {
    RuleBands bands =
        RuleBands.of(List.of(rule(OAK, SPRUCE, 1, 3), rule(STONE, SPRUCE, 2, 4)), HEIGHT);

    assertEquals(SPRUCE, target(bands, 1, OAK));
    assertNull(target(bands, 1, STONE));
    assertEquals(SPRUCE, target(bands, 3, OAK));
    assertEquals(SPRUCE, target(bands, 3, STONE));
    assertNull(target(bands, 4, OAK));
    assertEquals(4, bands.count());
  }

  @Test
  void aRangeAboveTheDesignChangesNothing() {
    RuleBands bands = RuleBands.of(List.of(rule(OAK, SPRUCE, 20, 25)), HEIGHT);

    assertTrue(bands.uniform());
    assertNull(target(bands, 9, OAK));
  }
}
