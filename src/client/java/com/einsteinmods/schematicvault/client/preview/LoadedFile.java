package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.edit.BlockTally;
import com.einsteinmods.schematicvault.core.edit.RuleBands;
import com.einsteinmods.schematicvault.core.edit.SchematicRewriter;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.nbt.NbtReader;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A design file read for the preview, kept while it stays among the session's recent volumes: its
 * cells, the bytes it was read from (the edited download rewrites them, never fetching them again),
 * the palette as the game resolved it, and what the block editor lists. Immutable; built on the
 * preview worker.
 *
 * @param bytes the file as downloaded (compressed): far smaller than the cells
 * @param tally blocks per type, as the editor lists them ({@link PreviewPalette#blockIds})
 * @param savedFormat the format its edited copy is saved in ({@link SchematicRewriter#savedFormat})
 */
record LoadedFile(
    VoxelVolume volume,
    byte[] bytes,
    PreviewPalette palette,
    BlockTally tally,
    SchematicFormat savedFormat) {
  static LoadedFile of(VoxelVolume volume, byte[] bytes, SchematicFormat format)
      throws NbtFormatException {
    PreviewPalette palette = PreviewPalette.resolve(volume);
    // Only a .schematic may be saved in another format: it is read again to tell which (small).
    SchematicFormat saved =
        format == SchematicFormat.SCHEMATIC
            ? SchematicRewriter.savedFormat(format, NbtReader.read(bytes))
            : format;
    return new LoadedFile(
        volume, bytes, palette, BlockTally.of(palette.blockIds(), volume.paletteCounts()), saved);
  }

  /**
   * The palette with {@code replacements} applied: one remapped palette per band of layers, one
   * pass over the palette for each, never over the cells.
   */
  EditedPalette edited(BlockReplacements replacements) {
    RuleBands bands = RuleBands.of(replacements.rules(), volume.sizeY());
    int size = palette.states().length;
    PreviewPalette[] palettes = new PreviewPalette[bands.count()];
    BlockEdits.@Nullable Edit[][] edits = new BlockEdits.Edit[bands.count()][size];
    for (int band = 0; band < palettes.length; band++) {
      boolean edited = false;
      for (int index = 0; index < size; index++) {
        String blockId = palette.blockIds()[index];
        BlockRule rule = blockId == null ? null : bands.rule(band, blockId);
        if (rule != null && !rule.keepsBlock()) {
          edits[band][index] =
              BlockEdits.edit(
                  palette.known()[index] ? palette.states()[index] : null,
                  palette.properties(index),
                  rule.targetBlock());
          edited |= edits[band][index] != null;
        }
      }
      palettes[band] = edited ? palette.with(edits[band], volume.paletteCounts()) : palette;
    }
    return new EditedPalette(palettes, edits, replacements, bands);
  }

  /** The editor's list for {@code edited} over the whole design. */
  BlockList blockList(EditedPalette edited) {
    RuleBands bands = edited.bands();
    BlockTally counted =
        bands.uniform()
            ? BlockTally.of(palette.blockIds(), new int[][] {volume.paletteCounts()}, bands)
            : BlockTally.of(palette.blockIds(), volume, LayerRange.ALL, bands);
    return blockList(edited, counted);
  }

  /**
   * The editor's list for {@code edited} counting only {@code range}: a block absent from it has no
   * row, a rule that decides none of its cells goes to {@link BlockList#otherRules}.
   */
  BlockList blockList(EditedPalette edited, LayerRange range) {
    return blockList(edited, BlockTally.of(palette.blockIds(), volume, range, edited.bands()));
  }

  private BlockList blockList(EditedPalette edited, BlockTally counted) {
    Set<BlockRule> lost = new HashSet<>();
    Set<BlockRule> dropped = new HashSet<>();
    collectWarnings(edited, lost, dropped);
    List<BlockList.Row> rows = new ArrayList<>(counted.entries().size());
    Set<BlockRule> listed = new HashSet<>();
    for (BlockTally.Entry entry : counted.entries()) {
      rows.add(row(entry.blockId(), entry.count(), entry.rule(), lost, dropped));
      if (entry.rule() != null) {
        listed.add(entry.rule());
      }
    }
    List<BlockList.Row> others = new ArrayList<>();
    for (BlockRule rule : edited.replacements().rules()) {
      if (!listed.contains(rule)) {
        others.add(row(rule.sourceBlock(), 0, rule, lost, dropped));
      }
    }
    return new BlockList(
        rows, others, counted.total(), counted.resultTypes(), !edited.replacements().isEmpty());
  }

  /** The rules that drop properties of a state, or block-entity data of a cell they change. */
  private void collectWarnings(EditedPalette edited, Set<BlockRule> lost, Set<BlockRule> dropped) {
    @Nullable String[] blockIds = palette.blockIds();
    RuleBands bands = edited.bands();
    for (int band = 0; band < bands.count(); band++) {
      for (int index = 0; index < blockIds.length; index++) {
        BlockEdits.Edit edit = edited.edits()[band][index];
        String blockId = blockIds[index];
        if (edit != null && blockId != null && edit.lostProperties()) {
          lost.add(bands.rule(band, blockId));
        }
      }
    }
    int layerCells = volume.sizeX() * volume.sizeZ();
    for (int cell : volume.blockEntities().keySet()) {
      int band = bands.bandOfY()[cell / layerCells];
      int entry = volume.cells()[cell];
      BlockEdits.Edit edit = edited.edits()[band][entry];
      String blockId = blockIds[entry];
      if (edit != null && blockId != null && edit.dropsData()) {
        dropped.add(bands.rule(band, blockId));
      }
    }
  }

  private BlockList.Row row(
      String blockId,
      long count,
      @Nullable BlockRule rule,
      Set<BlockRule> lost,
      Set<BlockRule> dropped) {
    return new BlockList.Row(
        blockId,
        BlockEdits.block(blockId),
        palette.standIns().get(blockId),
        count,
        rule,
        rule == null ? null : BlockEdits.block(rule.targetBlock()),
        lost.contains(rule),
        dropped.contains(rule));
  }

  /**
   * A file's palette with its replacements applied: what each band of layers reads.
   *
   * @param palettes what the mesh draws, per band
   * @param edits each entry's edit per band; null where it is kept
   * @param bands which band each layer reads
   */
  record EditedPalette(
      PreviewPalette[] palettes,
      BlockEdits.@Nullable Edit[][] edits,
      BlockReplacements replacements,
      RuleBands bands) {
    int[] bandOfY() {
      return bands.bandOfY();
    }

    /** The game's state of each palette index, per band. */
    BlockState[][] statesByBand() {
      BlockState[][] states = new BlockState[palettes.length][];
      for (int band = 0; band < states.length; band++) {
        states[band] = palettes[band].states();
      }
      return states;
    }

    /** Whether each palette index is an opaque full block, per band. */
    boolean[][] opaqueByBand() {
      boolean[][] opaque = new boolean[palettes.length][];
      for (int band = 0; band < opaque.length; band++) {
        opaque[band] = palettes[band].opaque();
      }
      return opaque;
    }

    /**
     * The blocks the game does not know that some band keeps, by id, with the vanilla block drawn
     * for each. By palette, not by cell: a block all of whose cells a range replaced stays listed
     * while another band keeps it.
     */
    SortedMap<String, Block> standIns() {
      if (palettes.length == 1) {
        return palettes[0].standIns();
      }
      SortedMap<String, Block> standIns = new TreeMap<>();
      for (PreviewPalette bandPalette : palettes) {
        standIns.putAll(bandPalette.standIns());
      }
      return standIns;
    }
  }
}
