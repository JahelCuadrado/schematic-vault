package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.edit.BlockStateRemap;
import com.einsteinmods.schematicvault.core.volume.BlockRenames;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.DataVersions;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's block state for each entry of a volume's palette, resolved once per entry (a design
 * uses a few hundred at most). States saved by an older game version, or with no version ({@link
 * DataVersions}), go through the game's own data fixer first ({@code DataFixer#update} with {@code
 * References.BLOCK_STATE}, as the game does when it loads an old structure), so renamed blocks and
 * properties still show; a name still unknown then goes through {@link BlockRenames}. Then the
 * lookup is lenient: a block the game does not know (a mod's the player lacks) is drawn as a
 * similar vanilla block ({@link StandInBlocks}, with the properties it supports and no saved
 * block-entity data; logged once per file), display only: edited files keep its id; an unknown
 * property or value keeps the block's default for it. Worker thread: registries are frozen, so
 * reads are safe. The block editor derives an edited palette from it ({@link #with}): a copy of a
 * few hundred entries, never a pass over the cells.
 *
 * @param states the state of each palette index
 * @param opaque whether each state is an opaque full block ({@code BlockState#isSolidRender})
 * @param keepsBlockEntity whether each entry's saved block-entity data is loaded (false once a
 *     replacement changed its block-entity type)
 * @param blockIds the block of each entry as the editor lists it: the game's id, the file's name
 *     for a block the game does not know; null for air
 * @param fixed each entry as the current game names it (after the data fixer)
 * @param known whether the game knows each entry's block (false for a stand-in)
 * @param standIns each block the game does not know and no replacement changed, by id, with the
 *     vanilla block drawn for it
 * @param standInBlocks cells drawn as a stand-in
 */
record PreviewPalette(
    BlockState[] states,
    boolean[] opaque,
    boolean[] keepsBlockEntity,
    @Nullable String[] blockIds,
    BlockStateString.Parsed[] fixed,
    boolean[] known,
    SortedMap<String, Block> standIns,
    long standInBlocks) {
  private static final Logger LOGGER = LoggerFactory.getLogger(PreviewPalette.class);

  /**
   * First data version whose block states are {@code {id, properties}} instead of {@code {Name,
   * Properties}} ({@code BlockStateFieldNamesFix}, schema 5006; {@code NbtUtils#packBlockState}).
   */
  private static final int FIELD_NAMES_VERSION = 5006;

  private static final String LEGACY_NAME = "Name";
  private static final String LEGACY_PROPERTIES = "Properties";
  private static final String NAME = "id";
  private static final String PROPERTIES = "properties";
  private static final BlockState AIR = Blocks.AIR.defaultBlockState();

  static PreviewPalette resolve(VoxelVolume volume) {
    Fixer fixer = Fixer.from(volume.dataVersion());
    int size = volume.palette().size();
    BlockState[] states = new BlockState[size];
    boolean[] opaque = new boolean[size];
    boolean[] keeps = new boolean[size];
    String[] blockIds = new String[size];
    BlockStateString.Parsed[] fixed = new BlockStateString.Parsed[size];
    boolean[] known = new boolean[size];
    for (int index = 0; index < size; index++) {
      keeps[index] = true;
      BlockStateString.Parsed parsed =
          index == VoxelVolume.AIR
              ? BlockStateString.parse(VoxelVolume.AIR_STATE)
              : fixer.fix(volume.palette().get(index));
      fixed[index] = parsed;
      BlockState state = index == VoxelVolume.AIR ? AIR : lookupOrRenamed(parsed);
      known[index] = state != null;
      if (state == null) {
        blockIds[index] = parsed.name();
        state = standIn(parsed);
        // The saved data belongs to the mod's block, not to the stand-in.
        keeps[index] = false;
      } else if (!state.isAir()) {
        blockIds[index] = BlockEdits.id(state.getBlock());
      }
      states[index] = state;
      opaque[index] = state.isSolidRender();
    }
    StandIns standIns = StandIns.of(states, blockIds, known, volume.paletteCounts());
    if (!standIns.byId().isEmpty()) {
      Map<String, String> shownAs = new LinkedHashMap<>();
      standIns.byId().forEach((blockId, block) -> shownAs.put(blockId, BlockEdits.id(block)));
      LOGGER.info(
          "3D preview: blocks the game does not know (mods not installed, data version {}), drawn"
              + " as similar blocks: {}",
          fixer.from(),
          shownAs);
    }
    return new PreviewPalette(
        states, opaque, keeps, blockIds, fixed, known, standIns.byId(), standIns.cells());
  }

  /** The vanilla state drawn for a block the game does not know ({@link StandInBlocks}). */
  private static BlockState standIn(BlockStateString.Parsed parsed) {
    StandInBlocks.StandIn standIn =
        StandInBlocks.of(parsed.name(), id -> BlockEdits.block(id) != null);
    Block block = BlockEdits.block(standIn.id());
    if (block == null) {
      // The placeholder is the one stand-in not checked against the registry.
      block = Blocks.WOOL.magenta();
    }
    return BlockEdits.state(
        block, BlockStateRemap.remap(parsed.properties(), BlockEdits.target(block)).properties());
  }

  /**
   * The stand-ins of a palette: entries the game does not know, with the block drawn for each.
   *
   * @param byId the stand-in block per id, sorted by id
   * @param cells the cells they fill
   */
  private record StandIns(SortedMap<String, Block> byId, long cells) {
    static StandIns of(
        BlockState[] states, @Nullable String[] blockIds, boolean[] known, int[] paletteCounts) {
      SortedMap<String, Block> byId = new TreeMap<>();
      long cells = 0;
      for (int index = 0; index < states.length; index++) {
        String blockId = blockIds[index];
        if (!known[index] && blockId != null) {
          byId.putIfAbsent(blockId, states[index].getBlock());
          cells += paletteCounts[index];
        }
      }
      return new StandIns(Collections.unmodifiableSortedMap(byId), cells);
    }
  }

  /**
   * This palette with the edited entries' states replaced.
   *
   * @param edits each entry's edit; null where it is kept
   * @param paletteCounts the volume's cells per entry
   */
  PreviewPalette with(BlockEdits.@Nullable Edit[] edits, int[] paletteCounts) {
    BlockState[] newStates = states.clone();
    boolean[] newOpaque = opaque.clone();
    boolean[] newKeeps = keepsBlockEntity.clone();
    boolean[] newKnown = known.clone();
    for (int index = 0; index < newStates.length; index++) {
      BlockEdits.Edit edit = edits[index];
      if (edit != null) {
        newStates[index] = edit.state();
        newOpaque[index] = edit.state().isSolidRender();
        newKeeps[index] = edit.keepBlockEntity();
        newKnown[index] = true;
      }
    }
    StandIns kept = StandIns.of(newStates, blockIds, newKnown, paletteCounts);
    return new PreviewPalette(
        newStates, newOpaque, newKeeps, blockIds, fixed, newKnown, kept.byId(), kept.cells());
  }

  /** An entry's properties as the game names them (as the file has them for an unknown block). */
  Map<String, String> properties(int index) {
    return known[index] ? BlockEdits.properties(states[index]) : fixed[index].properties();
  }

  /**
   * The game's data fixer from a file's data version ({@link DataVersions#forFixer}) to the running
   * game's, when it is older.
   */
  record Fixer(@Nullable DataFixer fixer, int from, int to, boolean unversioned) {
    static Fixer from(int dataVersion) {
      int current = SharedConstants.getCurrentVersion().dataVersion().version();
      int from = DataVersions.forFixer(dataVersion);
      DataFixer fixer = from > 0 && from < current ? DataFixers.getDataFixer() : null;
      return new Fixer(fixer, from, current, DataVersions.isUnversioned(dataVersion));
    }

    /** {@code state} as the current game names it; as written when no fix applies. */
    BlockStateString.Parsed fix(String state) {
      BlockStateString.Parsed parsed = BlockStateString.parse(state);
      DataFixer active = fixer;
      if (active == null) {
        return parsed;
      }
      BlockStateString.Parsed fixed = fixed(active, parsed, from, to, unversioned);
      return unversioned ? withFileProperties(fixed, parsed) : fixed;
    }
  }

  /**
   * {@code fixed} with the properties of the file's own state the block accepts. The flattening
   * fixer maps a state to its 1.12 metadata ({@code BlockStateData}, {@code
   * ChunkPalettedStorageFix} restores the rest from neighbours in a chunk), so it drops what a
   * state only knew from its neighbours and a file keeps: pane, fence and wire connections, stair
   * shapes, the facing of a door's upper half.
   */
  private static BlockStateString.Parsed withFileProperties(
      BlockStateString.Parsed fixed, BlockStateString.Parsed file) {
    Block block = BlockEdits.block(fixed.name());
    return block == null
        ? fixed
        : new BlockStateString.Parsed(
            fixed.name(),
            BlockStateRemap.overlay(
                fixed.properties(), file.properties(), BlockEdits.target(block)));
  }

  /** The game's state, trying {@link BlockRenames} when the game does not know the name. */
  private static @Nullable BlockState lookupOrRenamed(BlockStateString.Parsed parsed) {
    BlockState state = lookup(parsed);
    if (state != null) {
      return state;
    }
    String renamed = BlockRenames.currentName(parsed.name(), parsed.properties());
    return renamed.equals(parsed.name())
        ? null
        : lookup(new BlockStateString.Parsed(renamed, parsed.properties()));
  }

  /**
   * The state as the current game names it; unchanged when the fixer cannot read it. The fixer
   * keeps the field names of the version it reads and writes those of the version it reaches: the
   * input uses {@code from}'s, the output ({@code to}, the running game) {@code id}/{@code
   * properties}. A state without properties has no properties tag, as the game writes it ({@code
   * NbtUtils#writeStateProperties}): the flattening fixer only renames a state equal to one of its
   * 1.12 tags ({@code BlockStateData#upgradeBlockStateTag}), so {@code {Name: "minecraft:web"}}
   * matches and {@code {Name: "minecraft:web", Properties: {}}} does not. Only for a file without a
   * version: files that declare one keep the tag.
   */
  private static BlockStateString.Parsed fixed(
      DataFixer fixer, BlockStateString.Parsed parsed, int from, int to, boolean unversioned) {
    boolean legacyFields = from < FIELD_NAMES_VERSION;
    CompoundTag state = new CompoundTag();
    state.putString(legacyFields ? LEGACY_NAME : NAME, parsed.name());
    if (!unversioned || !parsed.properties().isEmpty()) {
      CompoundTag properties = new CompoundTag();
      parsed.properties().forEach(properties::putString);
      state.put(legacyFields ? LEGACY_PROPERTIES : PROPERTIES, properties);
    }
    Tag result;
    try {
      result =
          fixer
              .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, state), from, to)
              .getValue();
    } catch (RuntimeException exception) {
      // A state an old version never had (a modded block, a typo): look it up as written.
      return parsed;
    }
    if (!(result instanceof CompoundTag fixed)) {
      return parsed;
    }
    Map<String, String> fixedProperties = new LinkedHashMap<>();
    CompoundTag fixedTag = fixed.getCompoundOrEmpty(PROPERTIES);
    for (String key : fixedTag.keySet()) {
      fixedTag.getString(key).ifPresent(value -> fixedProperties.put(key, value));
    }
    return new BlockStateString.Parsed(fixed.getStringOr(NAME, parsed.name()), fixedProperties);
  }

  /** The game's state for a parsed state, or null when the game does not know the block. */
  static @Nullable BlockState lookup(BlockStateString.Parsed parsed) {
    Block block = BlockEdits.block(parsed.name());
    return block == null ? null : BlockEdits.state(block, parsed.properties());
  }
}
