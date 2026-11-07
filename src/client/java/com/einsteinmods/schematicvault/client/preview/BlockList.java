package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.edit.BlockRule;
import java.util.List;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The shown design's blocks as the editor lists them: one row per block type (all its states
 * together) and per rule deciding some of its cells, most common first, as the 3D shows them.
 * Doubles as a material list: {@code total} is the non-air blocks of the design or of the layers
 * listed once the rules apply, {@code types} the kinds of block among them. Made again after each
 * change, never per frame.
 *
 * @param rows the blocks of the layers listed
 * @param otherRules rules that decide no cell of those layers, to undo them from here too
 * @param edited whether any block is replaced
 */
public record BlockList(
    List<Row> rows, List<Row> otherRules, long total, int types, boolean edited) {
  public BlockList {
    rows = List.copyOf(rows);
    otherRules = List.copyOf(otherRules);
  }

  /**
   * A block type of the design.
   *
   * @param blockId the block's id (the file's name for a block the game does not know)
   * @param block the game's block; null when the game does not know it
   * @param shownAs the vanilla block drawn for a block the game does not know (display only: an
   *     edited file keeps the original id); null for a known block
   * @param count the cells of the row; 0 in {@link #otherRules}
   * @param rule the rule that decides these cells; null when they keep their block
   * @param replacement the block chosen instead (air removes it); null when kept
   * @param lostProperties whether the rule drops some of the block's properties (orientation,
   *     shape)
   * @param dropsData whether the rule drops block-entity data (contents, text) somewhere
   */
  public record Row(
      String blockId,
      @Nullable Block block,
      @Nullable Block shownAs,
      long count,
      @Nullable BlockRule rule,
      @Nullable Block replacement,
      boolean lostProperties,
      boolean dropsData) {}
}
