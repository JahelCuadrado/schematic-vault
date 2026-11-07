package com.einsteinmods.schematicvault.core.edit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A palette after its changes, with entries that became identical merged into the first of them
 * (Litematica's and WorldEdit's palettes map each state to one index, so duplicates are never
 * written). Nothing else is dropped, even unused entries. Indices move as little as possible: an
 * entry keeps its index unless it sat past the new end, in which case it takes a slot a merged
 * entry freed; so a structure's block list changes only where it must.
 *
 * @param oldToNew the new index of each old entry
 * @param kept the old entry each new entry comes from (the first with its key)
 */
record PaletteMerge(int[] oldToNew, List<Integer> kept) {
  private static final int UNSET = -1;

  PaletteMerge {
    kept = List.copyOf(kept);
  }

  /** Merges the entries whose {@code keys} (what each old entry becomes) are equal. */
  static <K> PaletteMerge of(List<K> keys) {
    int count = keys.size();
    int[] first = new int[count];
    Map<K, Integer> byKey = new HashMap<>();
    int distinct = 0;
    for (int index = 0; index < count; index++) {
      Integer known = byKey.putIfAbsent(keys.get(index), index);
      first[index] = known == null ? index : known;
      distinct += known == null ? 1 : 0;
    }
    int[] newIndexOfFirst = new int[count];
    Arrays.fill(newIndexOfFirst, UNSET);
    Integer[] kept = new Integer[distinct];
    List<Integer> movedFirsts = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      if (first[index] != index) {
        continue;
      }
      if (index < distinct) {
        newIndexOfFirst[index] = index;
        kept[index] = index;
      } else {
        movedFirsts.add(index);
      }
    }
    int slot = 0;
    for (int moved : movedFirsts) {
      while (kept[slot] != null) {
        slot++;
      }
      newIndexOfFirst[moved] = slot;
      kept[slot] = moved;
    }
    int[] oldToNew = new int[count];
    for (int index = 0; index < count; index++) {
      oldToNew[index] = newIndexOfFirst[first[index]];
    }
    return new PaletteMerge(oldToNew, Arrays.asList(kept));
  }

  int size() {
    return kept.size();
  }
}
