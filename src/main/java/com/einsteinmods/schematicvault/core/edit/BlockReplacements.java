package com.einsteinmods.schematicvault.core.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * The block replacements the player chose for one design file: an ordered list of {@link
 * BlockRule}, the newest last. For a cell, the last rule covering its original block and its layer
 * wins ({@link RuleBands}). Immutable: each change gives a new instance.
 *
 * @param rules in the order they were chosen
 */
public record BlockReplacements(List<BlockRule> rules) {
  public static final BlockReplacements NONE = new BlockReplacements(List.of());

  public BlockReplacements {
    rules = List.copyOf(rules);
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }

  /**
   * These replacements with {@code rule} as the newest: it takes the place of the rule of the same
   * block and layers. A rule keeping its block removes the rules of that block it covers, and is
   * only kept where a wider rule still changes the block.
   */
  public BlockReplacements with(BlockRule rule) {
    List<BlockRule> kept = new ArrayList<>(rules.size() + 1);
    boolean changedAround = false;
    for (BlockRule existing : rules) {
      boolean sameBlock = existing.sourceBlock().equals(rule.sourceBlock());
      if (existing.sameScope(rule)
          || rule.keepsBlock() && sameBlock && rule.layers().contains(existing.layers())) {
        continue;
      }
      kept.add(existing);
      changedAround |= sameBlock && existing.layers().overlaps(rule.layers());
    }
    if (!rule.keepsBlock() || changedAround) {
      kept.add(rule);
    }
    return kept.isEmpty() ? NONE : new BlockReplacements(kept);
  }

  /** These replacements without {@code rule}. */
  public BlockReplacements without(BlockRule rule) {
    if (!rules.contains(rule)) {
      return this;
    }
    List<BlockRule> kept = new ArrayList<>(rules);
    kept.remove(rule);
    return kept.isEmpty() ? NONE : new BlockReplacements(kept);
  }
}
