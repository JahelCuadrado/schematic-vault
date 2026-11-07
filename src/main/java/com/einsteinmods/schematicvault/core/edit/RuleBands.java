package com.einsteinmods.schematicvault.core.edit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A design's Y layers grouped by the rules that win on them: layers of one band share one set of
 * winners, so each band needs one remapped palette and the cells are never copied ({@code
 * bandOf(y)} says which palette a cell reads). Layers with the same winners share a band even when
 * they are not next to each other. At most {@code 2 * rules + 1} bands; computed in microseconds
 * (rules squared, nothing per cell). Immutable: {@link #bandOfY} must not be written.
 */
public final class RuleBands {
  private final int[] bandOfY;
  private final List<Map<String, BlockRule>> winners;

  private RuleBands(int[] bandOfY, List<Map<String, BlockRule>> winners) {
    this.bandOfY = bandOfY;
    this.winners = winners;
  }

  /**
   * The bands of a design of {@code height} layers.
   *
   * @param rules in the order they were chosen: the last rule covering a block and a layer wins
   */
  public static RuleBands of(List<BlockRule> rules, int height) {
    int[] bandOfY = new int[height];
    TreeSet<Integer> starts = new TreeSet<>();
    starts.add(0);
    for (BlockRule rule : rules) {
      addStart(starts, rule.layers().bottom(), height);
      if (rule.layers().top() < height) {
        addStart(starts, rule.layers().top() + 1, height);
      }
    }
    List<Map<String, BlockRule>> bands = new ArrayList<>();
    Map<Map<String, BlockRule>, Integer> known = new HashMap<>();
    for (int start : starts) {
      Integer next = starts.higher(start);
      Map<String, BlockRule> atStart = winnersAt(rules, start);
      Integer band = known.get(atStart);
      if (band == null) {
        band = bands.size();
        bands.add(atStart);
        known.put(atStart, band);
      }
      Arrays.fill(bandOfY, start, next == null ? height : next, band);
    }
    return new RuleBands(bandOfY, List.copyOf(bands));
  }

  private static void addStart(TreeSet<Integer> starts, int layer, int height) {
    if (layer < height) {
      starts.add(layer);
    }
  }

  private static Map<String, BlockRule> winnersAt(List<BlockRule> rules, int layer) {
    Map<String, BlockRule> atLayer = new HashMap<>();
    for (BlockRule rule : rules) {
      if (rule.layers().contains(layer)) {
        atLayer.put(rule.sourceBlock(), rule);
      }
    }
    return atLayer;
  }

  /** How many bands: at least one, even without rules. */
  public int count() {
    return winners.size();
  }

  /** Whether every layer reads the same palette: no rule's range shows in the design. */
  public boolean uniform() {
    return count() == 1;
  }

  /** The band of each Y layer, shared: never written. */
  public int[] bandOfY() {
    return bandOfY;
  }

  /** The rule that decides {@code sourceBlock} on {@code band}; null when the block is kept. */
  public @Nullable BlockRule rule(int band, String sourceBlock) {
    return winners.get(band).get(sourceBlock);
  }
}
