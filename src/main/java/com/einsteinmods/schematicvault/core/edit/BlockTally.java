package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How many blocks of each type a design holds: every state of a block counted together, most common
 * first (ties by id). Air is left out, so the counts add up to the design's non-air cells. Counted
 * through {@link RuleBands}, a block has one entry for the cells it keeps and one per rule deciding
 * some of its cells; cells a rule removes keep their entry (the rule is undone from there) but
 * leave the total, so it is what the design holds after its rules.
 *
 * @param entries one per block type, or per block and rule
 * @param total non-air blocks in the end
 */
public record BlockTally(List<Entry> entries, long total) {
  private static final Comparator<Entry> MOST_COMMON_FIRST =
      Comparator.comparingLong(Entry::count).reversed().thenComparing(Entry::blockId);

  public BlockTally {
    entries = List.copyOf(entries);
  }

  /**
   * A block type and its count.
   *
   * @param blockId the file's block
   * @param rule the rule that changed these cells; null when they keep their block
   */
  public record Entry(String blockId, long count, @Nullable BlockRule rule) {
    public Entry(String blockId, long count) {
      this(blockId, count, null);
    }

    /** The block these cells hold in the end. */
    public String resultBlock() {
      return rule == null ? blockId : rule.targetBlock();
    }
  }

  /** The grouping of a tally through bands. */
  private record BandKey(String blockId, @Nullable BlockRule rule) {}

  /** The tally by the block names the volume's palette holds, as written in the file. */
  public static BlockTally of(VoxelVolume volume) {
    List<String> palette = volume.palette();
    String[] blockIds = new String[palette.size()];
    for (int index = 0; index < blockIds.length; index++) {
      String state = palette.get(index);
      blockIds[index] = BlockStateString.isAir(state) ? null : BlockStateString.parse(state).name();
    }
    return of(blockIds, volume.paletteCounts());
  }

  /**
   * The tally of a range of the volume's Y layers: a block absent from them has no entry. One pass
   * over the range's cells.
   *
   * @param blockIds the block of each palette entry; null for air (not counted)
   * @param range Y layers counted, cut to the volume's height
   */
  public static BlockTally of(@Nullable String[] blockIds, VoxelVolume volume, LayerRange range) {
    int layerCells = volume.sizeX() * volume.sizeZ();
    int top = Math.min(range.top(), volume.sizeY() - 1);
    int[] cells = volume.cells();
    int[] counts = new int[volume.paletteCounts().length];
    int end = (top + 1) * layerCells;
    for (int index = range.bottom() * layerCells; index < end; index++) {
      counts[cells[index]]++;
    }
    return of(blockIds, counts);
  }

  /**
   * The tally of a range of the volume's Y layers after {@code bands}: one pass over the range's
   * cells.
   *
   * @param blockIds the file's block of each palette entry; null for air (not counted)
   * @param range Y layers counted, cut to the volume's height
   */
  public static BlockTally of(
      @Nullable String[] blockIds, VoxelVolume volume, LayerRange range, RuleBands bands) {
    int layerCells = volume.sizeX() * volume.sizeZ();
    int top = Math.min(range.top(), volume.sizeY() - 1);
    int[] cells = volume.cells();
    int[] bandOfY = bands.bandOfY();
    int[][] countsByBand = new int[bands.count()][volume.paletteCounts().length];
    for (int y = range.bottom(); y <= top; y++) {
      int[] counts = countsByBand[bandOfY[y]];
      int end = (y + 1) * layerCells;
      for (int index = y * layerCells; index < end; index++) {
        counts[cells[index]]++;
      }
    }
    return of(blockIds, countsByBand, bands);
  }

  /**
   * The tally of a palette's entries per band.
   *
   * @param blockIds the file's block of each palette entry; null for air (not counted)
   * @param countsByBand cells holding each palette entry, per band
   */
  public static BlockTally of(@Nullable String[] blockIds, int[][] countsByBand, RuleBands bands) {
    Map<BandKey, Long> byKey = new LinkedHashMap<>();
    for (int band = 0; band < countsByBand.length; band++) {
      int[] counts = countsByBand[band];
      int size = Math.min(blockIds.length, counts.length);
      for (int index = 0; index < size; index++) {
        String blockId = blockIds[index];
        if (blockId != null && counts[index] != 0) {
          byKey.merge(
              new BandKey(blockId, bands.rule(band, blockId)), (long) counts[index], Long::sum);
        }
      }
    }
    long total = 0;
    List<Entry> entries = new ArrayList<>(byKey.size());
    for (Map.Entry<BandKey, Long> entry : byKey.entrySet()) {
      Entry counted = new Entry(entry.getKey().blockId(), entry.getValue(), entry.getKey().rule());
      entries.add(counted);
      total += BlockStateString.isAir(counted.resultBlock()) ? 0 : counted.count();
    }
    entries.sort(MOST_COMMON_FIRST);
    return new BlockTally(entries, total);
  }

  /** The kinds of block the design holds in the end: what a material list of the result shows. */
  public int resultTypes() {
    Set<String> blocks = new HashSet<>();
    for (Entry entry : entries) {
      if (!BlockStateString.isAir(entry.resultBlock())) {
        blocks.add(entry.resultBlock());
      }
    }
    return blocks.size();
  }

  /**
   * The tally of a palette whose entries belong to {@code blockIds}.
   *
   * @param blockIds the block of each palette entry; null for air (not counted)
   * @param counts cells holding each palette entry
   */
  public static BlockTally of(@Nullable String[] blockIds, int[] counts) {
    Map<String, Long> byBlock = new LinkedHashMap<>();
    long total = 0;
    int size = Math.min(blockIds.length, counts.length);
    for (int index = 0; index < size; index++) {
      String blockId = blockIds[index];
      if (blockId == null || counts[index] == 0) {
        continue;
      }
      byBlock.merge(blockId, (long) counts[index], Long::sum);
      total += counts[index];
    }
    List<Entry> entries = new ArrayList<>(byBlock.size());
    for (Map.Entry<String, Long> entry : byBlock.entrySet()) {
      entries.add(new Entry(entry.getKey(), entry.getValue()));
    }
    entries.sort(MOST_COMMON_FIRST);
    return new BlockTally(entries, total);
  }
}
