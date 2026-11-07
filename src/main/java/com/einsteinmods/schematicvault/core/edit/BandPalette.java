package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A file's palette while cells are rewritten layer by layer: its entries stay where they are, as
 * the file writes them (unedited cells and modded ids keep their text), and a new state is added
 * after them the first time a cell needs it, unless the palette already holds that state, in any
 * property order: Litematica's and WorldEdit's palettes map a state to one index. A structure has
 * several palettes (variants) with their entries aligned: a new entry goes in all of them, and two
 * entries are the same only when they match in every one.
 */
final class BandPalette {
  private static final int UNSET = -1;
  private static final char PROPERTIES_START = '[';

  private final LayerChanges changes;
  private final List<List<@Nullable String>> variants;
  private final Map<List<String>, Integer> indexByKey = new HashMap<>();
  private final BitSet air = new BitSet();
  private final int[][] newIndices;
  private final boolean[][] dropsBlockEntity;

  /**
   * @param variants the file's palettes, of the same size; a null entry is an index the file does
   *     not list (it reads as air and stays as it is)
   */
  BandPalette(List<List<@Nullable String>> variants, LayerChanges changes) {
    this.changes = changes;
    this.variants = new ArrayList<>(variants.size());
    variants.forEach(variant -> this.variants.add(new ArrayList<>(variant)));
    int count = variants.getFirst().size();
    for (int index = 0; index < count; index++) {
      List<String> key = new ArrayList<>(variants.size());
      boolean allAir = true;
      for (List<@Nullable String> variant : variants) {
        String state = variant.get(index);
        allAir &= state == null || BlockStateString.isAir(state);
        if (state != null) {
          key.add(canonical(state));
        }
      }
      air.set(index, allAir);
      if (key.size() == variants.size()) {
        indexByKey.putIfAbsent(key, index);
      }
    }
    this.newIndices = new int[changes.bands()][count];
    for (int[] band : newIndices) {
      Arrays.fill(band, UNSET);
    }
    this.dropsBlockEntity = new boolean[changes.bands()][count];
  }

  /** A state as a key: its name and properties in one order, whatever the file's order. */
  private static String canonical(String state) {
    if (state.indexOf(PROPERTIES_START) < 0) {
      return state.strip();
    }
    BlockStateString.Parsed parsed = BlockStateString.parse(state);
    return parsed.name() + new TreeMap<>(parsed.properties());
  }

  /** What entry {@code old} of the file becomes on {@code band}. */
  int newIndex(int band, int old) {
    int known = newIndices[band][old];
    return known != UNSET ? known : resolve(band, old);
  }

  private int resolve(int band, int old) {
    List<String> states = new ArrayList<>(variants.size());
    boolean changed = false;
    boolean drop = false;
    for (List<@Nullable String> variant : variants) {
      String state = variant.get(old);
      if (state == null) {
        newIndices[band][old] = old;
        return old;
      }
      StateChange change = changes.mapper().change(band, state);
      if (change == null) {
        states.add(state);
      } else {
        changed = true;
        states.add(change.state());
        drop |= !change.keepBlockEntity() || BlockStateString.isAir(change.state());
      }
    }
    dropsBlockEntity[band][old] = drop;
    int index = changed ? indexOf(states) : old;
    newIndices[band][old] = index;
    return index;
  }

  /** The entry holding {@code states}, one per variant, added when the palette lacks it. */
  private int indexOf(List<String> states) {
    List<String> key = new ArrayList<>(states.size());
    states.forEach(state -> key.add(canonical(state)));
    Integer known = indexByKey.get(key);
    if (known != null) {
      return known;
    }
    int index = size();
    boolean allAir = true;
    for (int variant = 0; variant < states.size(); variant++) {
      variants.get(variant).add(states.get(variant));
      allAir &= BlockStateString.isAir(states.get(variant));
    }
    air.set(index, allAir);
    indexByKey.put(key, index);
    return index;
  }

  /** Whether entry {@code old}'s block-entity data goes on {@code band}: its block type changed. */
  boolean dropsBlockEntity(int band, int old) {
    newIndex(band, old);
    return dropsBlockEntity[band][old];
  }

  /** Whether an entry is air in every variant. */
  boolean isAir(int index) {
    return air.get(index);
  }

  /** The entry of plain air, added when the palette lacks it. */
  int airIndex() {
    List<String> states = new ArrayList<>(variants.size());
    variants.forEach(variant -> states.add(VoxelVolume.AIR_STATE));
    return indexOf(states);
  }

  /** Entries so far: the file's, then the new ones. */
  int size() {
    return variants.getFirst().size();
  }

  /** The state of an entry in a variant, as the file writes it. */
  String state(int variant, int index) {
    String state = variants.get(variant).get(index);
    return state == null ? VoxelVolume.AIR_STATE : state;
  }
}
