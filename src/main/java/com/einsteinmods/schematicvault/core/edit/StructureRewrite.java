package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ListTag;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Changes the palette of a vanilla structure ({@code .nbt}): its {@code palette}, or every list of
 * {@code palettes} (the random variants of vanilla's own structures, index-aligned), then each
 * {@code blocks[].state} for the merged indices and {@code blocks[].nbt} dropped where the block
 * changed type. Entries merge only when they become equal in every palette. A block replaced by air
 * stays listed with the air state (the game places air there), as the game saves air it captured.
 *
 * <p>Changes limited to some layers ({@link #rewriteLayers}) keep the palettes and add the new
 * states after them, in every palette at the same index ({@link BandPalette}), then take each block
 * entry's new state by its Y: a block removed in every palette loses its entry.
 */
final class StructureRewrite {
  private static final String PALETTE = "palette";
  private static final String PALETTES = "palettes";
  private static final String BLOCKS = "blocks";
  private static final String STATE = "state";
  private static final String NBT = "nbt";
  private static final String POSITION = "pos";
  private static final int AXES = 3;

  private StructureRewrite() {}

  private static boolean singlePalette(CompoundTag root) {
    return !root.compounds(PALETTE).isEmpty() || !root.contains(PALETTES);
  }

  /** The file's palettes (the variants), checked to be index-aligned. */
  private static List<List<CompoundTag>> palettes(CompoundTag root, boolean single)
      throws VolumeFormatException {
    List<List<CompoundTag>> palettes = new ArrayList<>();
    if (single) {
      palettes.add(root.compounds(PALETTE));
    } else {
      for (NbtTag palette : root.list(PALETTES)) {
        if (palette instanceof ListTag list) {
          palettes.add(list.compounds());
        }
      }
    }
    for (List<CompoundTag> palette : palettes) {
      if (palette.size() != palettes.getFirst().size()) {
        throw VolumeFormatException.unreadable("palettes of different sizes");
      }
    }
    return palettes;
  }

  static CompoundTag rewrite(CompoundTag root, StateMapper mapper) throws VolumeFormatException {
    boolean single = singlePalette(root);
    List<List<CompoundTag>> palettes = palettes(root, single);
    if (palettes.isEmpty()) {
      return root;
    }
    int count = palettes.getFirst().size();
    @Nullable StateChange[][] changes = new StateChange[palettes.size()][count];
    List<List<String>> keys = new ArrayList<>(count);
    boolean[] dropBlockEntity = new boolean[count];
    boolean changed = false;
    for (int index = 0; index < count; index++) {
      List<String> key = new ArrayList<>(palettes.size());
      for (int variant = 0; variant < palettes.size(); variant++) {
        String state = BlockStateString.of(palettes.get(variant).get(index));
        StateChange change = mapper.change(state);
        changes[variant][index] = change;
        changed |= change != null;
        dropBlockEntity[index] |= change != null && !change.keepBlockEntity();
        key.add(change == null ? state : change.state());
      }
      keys.add(key);
    }
    if (!changed) {
      return root;
    }
    PaletteMerge merge = PaletteMerge.of(keys);
    int[] oldToNew = merge.oldToNew();

    List<NbtTag> blocks = new ArrayList<>();
    for (NbtTag element : root.list(BLOCKS)) {
      if (!(element instanceof CompoundTag block)) {
        blocks.add(element);
        continue;
      }
      int state = block.intValue(STATE, -1);
      if (state < 0 || state >= count) {
        throw VolumeFormatException.unreadable("block state outside the palette");
      }
      if (oldToNew[state] == state && !dropBlockEntity[state]) {
        // Most blocks keep their index: the same tag, no copy (a structure may list millions).
        blocks.add(block);
        continue;
      }
      CompoundTag rewritten = block.with(STATE, new IntTag(oldToNew[state]));
      if (dropBlockEntity[state]) {
        rewritten = rewritten.without(Set.of(NBT));
      }
      blocks.add(rewritten);
    }

    List<NbtTag> newPalettes = new ArrayList<>(palettes.size());
    for (int variant = 0; variant < palettes.size(); variant++) {
      List<NbtTag> entries = new ArrayList<>(merge.size());
      for (int old : merge.kept()) {
        StateChange change = changes[variant][old];
        entries.add(
            change == null
                ? palettes.get(variant).get(old)
                : BlockStateString.compound(change.state()));
      }
      newPalettes.add(new ListTag(entries));
    }
    return withBlocks(root, blocks, newPalettes, single);
  }

  private static CompoundTag withBlocks(
      CompoundTag root, List<NbtTag> blocks, List<NbtTag> newPalettes, boolean single) {
    CompoundTag result = root.with(BLOCKS, new ListTag(blocks));
    return single
        ? result.with(PALETTE, newPalettes.getFirst())
        : result.with(PALETTES, new ListTag(newPalettes));
  }

  /** The new states go after the palettes; each block entry takes the state of its layer. */
  static CompoundTag rewriteLayers(CompoundTag root, LayerChanges changes)
      throws VolumeFormatException {
    boolean single = singlePalette(root);
    List<List<CompoundTag>> palettes = palettes(root, single);
    if (palettes.isEmpty()) {
      return root;
    }
    int count = palettes.getFirst().size();
    List<List<@Nullable String>> variants = new ArrayList<>(palettes.size());
    for (List<CompoundTag> palette : palettes) {
      List<@Nullable String> states = new ArrayList<>(count);
      for (CompoundTag entry : palette) {
        states.add(BlockStateString.of(entry));
      }
      variants.add(states);
    }
    BandPalette bands = new BandPalette(variants, changes);
    int[] bandOfY = changes.bandOfY();
    boolean changed = false;
    List<NbtTag> blocks = new ArrayList<>();
    for (NbtTag element : root.list(BLOCKS)) {
      if (!(element instanceof CompoundTag block)) {
        blocks.add(element);
        continue;
      }
      int state = block.intValue(STATE, -1);
      if (state < 0 || state >= count) {
        throw VolumeFormatException.unreadable("block state outside the palette");
      }
      int y = layer(block);
      if (y < 0 || y >= bandOfY.length) {
        blocks.add(block);
        continue;
      }
      int band = bandOfY[y];
      int newState = bands.newIndex(band, state);
      if (bands.isAir(newState) && !bands.isAir(state)) {
        changed = true;
        continue;
      }
      boolean dropBlockEntity = bands.dropsBlockEntity(band, state);
      if (newState == state && !dropBlockEntity) {
        blocks.add(block);
        continue;
      }
      changed = true;
      CompoundTag rewritten = block.with(STATE, new IntTag(newState));
      blocks.add(dropBlockEntity ? rewritten.without(Set.of(NBT)) : rewritten);
    }
    if (!changed) {
      return root;
    }
    List<NbtTag> newPalettes = new ArrayList<>(palettes.size());
    for (int variant = 0; variant < palettes.size(); variant++) {
      List<NbtTag> entries = new ArrayList<>(palettes.get(variant));
      for (int index = count; index < bands.size(); index++) {
        entries.add(BlockStateString.compound(bands.state(variant, index)));
      }
      newPalettes.add(new ListTag(entries));
    }
    return withBlocks(root, blocks, newPalettes, single);
  }

  /** The Y of a block entry; -1 when it has no position (the reader skips those). */
  private static int layer(CompoundTag block) {
    List<NbtTag> position = block.list(POSITION);
    return position.size() == AXES ? position.get(1).number().map(Number::intValue).orElse(-1) : -1;
  }
}
