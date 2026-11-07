package com.einsteinmods.schematicvault.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.volume.LayerRange;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockReplacementsTest {
  private static final String OAK = "minecraft:oak_planks";
  private static final String SPRUCE = "minecraft:spruce_planks";
  private static final String STONE = "minecraft:stone";

  private static BlockRule rule(String target, int bottom, int top) {
    return new BlockRule(OAK, target, new LayerRange(bottom, top));
  }

  private static BlockRule whole(String target) {
    return new BlockRule(OAK, target, LayerRange.ALL);
  }

  @Test
  void rulesAccumulateInTheOrderChosen() {
    BlockReplacements replacements =
        BlockReplacements.NONE.with(rule(SPRUCE, 5, 12)).with(rule(STONE, 13, 20));

    assertEquals(List.of(rule(SPRUCE, 5, 12), rule(STONE, 13, 20)), replacements.rules());
  }

  @Test
  void aNewRuleForTheSameBlockAndLayersReplacesTheOldOneAsTheNewest() {
    BlockReplacements replacements =
        BlockReplacements.NONE
            .with(rule(SPRUCE, 5, 12))
            .with(rule(STONE, 13, 20))
            .with(rule(STONE, 5, 12));

    assertEquals(List.of(rule(STONE, 13, 20), rule(STONE, 5, 12)), replacements.rules());
  }

  @Test
  void aRuleOverOtherLayersOfTheBlockIsAnotherRule() {
    BlockReplacements replacements =
        BlockReplacements.NONE.with(rule(SPRUCE, 5, 12)).with(rule(STONE, 5, 13));

    assertEquals(2, replacements.rules().size());
  }

  @Test
  void undoingARuleLeavesTheOthers() {
    BlockReplacements replacements =
        BlockReplacements.NONE.with(rule(SPRUCE, 5, 12)).with(rule(STONE, 13, 20));

    assertEquals(List.of(rule(STONE, 13, 20)), replacements.without(rule(SPRUCE, 5, 12)).rules());
  }

  @Test
  void undoingTheLastRuleLeavesNone() {
    assertSame(
        BlockReplacements.NONE, BlockReplacements.NONE.with(whole(SPRUCE)).without(whole(SPRUCE)));
  }

  @Test
  void undoingARuleThatIsNotThereChangesNothing() {
    BlockReplacements replacements = BlockReplacements.NONE.with(whole(SPRUCE));

    assertSame(replacements, replacements.without(rule(SPRUCE, 5, 12)));
  }

  @Test
  void choosingTheSameBlockEverywhereUndoesEveryRuleOfIt() {
    BlockRule other = new BlockRule(STONE, SPRUCE, LayerRange.ALL);
    BlockReplacements replacements =
        BlockReplacements.NONE.with(rule(SPRUCE, 5, 12)).with(other).with(whole(STONE));

    assertEquals(List.of(other), replacements.with(whole(OAK)).rules());
  }

  @Test
  void choosingTheSameBlockInSomeLayersIsKeptOnlyUnderAWiderRule() {
    assertTrue(BlockReplacements.NONE.with(rule(OAK, 5, 12)).isEmpty());

    BlockReplacements around = BlockReplacements.NONE.with(whole(SPRUCE)).with(rule(OAK, 5, 12));
    assertEquals(List.of(whole(SPRUCE), rule(OAK, 5, 12)), around.rules());
    assertTrue(around.rules().getLast().keepsBlock());
  }
}
