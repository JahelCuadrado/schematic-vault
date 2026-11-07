package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.volume.LayerRange;

/**
 * One replacement the player chose: every state of a block of the file becomes a new block on a
 * range of Y layers ({@link LayerRange#ALL} for the whole design). A rule always looks at the
 * file's original block, never at what another rule made of it; where several rules of a block
 * overlap, the last one wins ({@link RuleBands}). {@link BlockStateRemap} keeps what properties it
 * can.
 *
 * @param sourceBlock the block of the file, as the editor lists it
 * @param targetBlock the new block; {@code minecraft:air} removes the old one, the source itself
 *     keeps it on these layers (under a wider rule of the same block)
 * @param layers the Y layers it applies to
 */
public record BlockRule(String sourceBlock, String targetBlock, LayerRange layers) {
  /** Whether it stands for the same replacement slot: same block, same layers. */
  public boolean sameScope(BlockRule other) {
    return sourceBlock.equals(other.sourceBlock) && layers.equals(other.layers);
  }

  /** Whether it keeps the file's block on its layers. */
  public boolean keepsBlock() {
    return sourceBlock.equals(targetBlock);
  }
}
