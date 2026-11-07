package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.edit.LayerChanges;
import com.einsteinmods.schematicvault.core.edit.LitematicWriter;
import com.einsteinmods.schematicvault.core.edit.SchematicRewriter;
import com.einsteinmods.schematicvault.core.edit.StateChange;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.nbt.NbtReader;
import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtWriter;
import com.einsteinmods.schematicvault.core.volume.BlockStateString;
import com.einsteinmods.schematicvault.core.volume.McEditReader;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;
import com.einsteinmods.schematicvault.core.volume.VoxelVolume;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Writes the edited copy of a design file from the bytes the preview read. Litematic, Sponge and
 * structure files keep their own format ({@link SchematicRewriter}): without a rule limited to some
 * layers, everything but the palette; with one, also the cells of those layers. A legacy MCEdit
 * file is converted into a {@code .litematic} at the running game's data version: its states as the
 * game resolved them for the preview, its block entities (inventories included) upgraded by the
 * game's data fixer from 1.12. Preview worker.
 */
final class EditedFiles {
  private static final String ID = "id";

  /** The one band of a design no rule splits. */
  private static final int WHOLE_DESIGN_BAND = 0;

  private EditedFiles() {}

  /**
   * The edited file.
   *
   * @param name the name the player gave it (a converted file's Litematica name)
   * @param author the design's authors (a converted file's Litematica author)
   */
  static EditedFile write(
      LoadedFile file,
      LoadedFile.EditedPalette edited,
      SchematicFormat format,
      String name,
      String author,
      long timeMillis)
      throws VolumeFormatException, NbtFormatException {
    NbtTag.CompoundTag root = NbtReader.read(file.bytes());
    SchematicFormat saved = SchematicRewriter.savedFormat(format, root);
    NbtTag.CompoundTag written;
    if (format == SchematicFormat.SCHEMATIC && saved == SchematicFormat.LITEMATIC) {
      written = converted(file, edited, root, name, author, timeMillis);
    } else {
      LayerChanges changes = changes(file, edited);
      written =
          edited.bands().uniform()
              ? SchematicRewriter.rewrite(
                  format, root, state -> changes.mapper().change(WHOLE_DESIGN_BAND, state))
              : SchematicRewriter.rewrite(format, root, changes);
    }
    return new EditedFile(NbtWriter.gzip(written), saved);
  }

  /**
   * What each of the file's states becomes per band: the volume's entries as the editor edited
   * them, any other state (another palette of a structure) resolved and replaced the same way.
   */
  private static LayerChanges changes(LoadedFile file, LoadedFile.EditedPalette edited) {
    VoxelVolume volume = file.volume();
    Map<String, Integer> indices = new HashMap<>();
    for (int index = 0; index < volume.palette().size(); index++) {
      indices.put(volume.palette().get(index), index);
    }
    PreviewPalette.Fixer fixer = PreviewPalette.Fixer.from(volume.dataVersion());
    return new LayerChanges(
        edited.bandOfY(),
        edited.bands().count(),
        (band, state) -> {
          Integer index = indices.get(state);
          BlockEdits.Edit edit =
              index != null ? edited.edits()[band][index] : unlisted(fixer, state, edited, band);
          return edit == null ? null : new StateChange(edit.text(), edit.keepBlockEntity());
        });
  }

  private static BlockEdits.@Nullable Edit unlisted(
      PreviewPalette.Fixer fixer, String state, LoadedFile.EditedPalette edited, int band) {
    if (BlockStateString.isAir(state)) {
      return null;
    }
    BlockStateString.Parsed parsed = fixer.fix(state);
    BlockState resolved = PreviewPalette.lookup(parsed);
    if (resolved != null && resolved.isAir()) {
      return null;
    }
    String blockId = resolved == null ? parsed.name() : BlockEdits.id(resolved.getBlock());
    BlockRule rule = edited.bands().rule(band, blockId);
    return rule == null || rule.keepsBlock()
        ? null
        : BlockEdits.edit(
            resolved,
            resolved == null ? parsed.properties() : BlockEdits.properties(resolved),
            rule.targetBlock());
  }

  private static NbtTag.CompoundTag converted(
      LoadedFile file,
      LoadedFile.EditedPalette edited,
      NbtTag.CompoundTag root,
      String name,
      String author,
      long timeMillis) {
    VoxelVolume volume = file.volume();
    List<List<String>> statesByBand = new ArrayList<>(edited.palettes().length);
    for (int band = 0; band < edited.palettes().length; band++) {
      statesByBand.add(convertedStates(volume, edited, band));
    }
    int layerCells = volume.sizeX() * volume.sizeZ();
    int current = SharedConstants.getCurrentVersion().dataVersion().version();
    BlockEntityFixer blockEntities = new BlockEntityFixer(volume.blockEntityDataVersion(), current);
    Map<Integer, NbtTag.CompoundTag> kept = new HashMap<>();
    for (Map.Entry<Integer, NbtTag.CompoundTag> entry :
        McEditReader.blockEntities(root).entrySet()) {
      int entryIndex = volume.cells()[entry.getKey()];
      PreviewPalette palette = edited.palettes()[edited.bandOfY()[entry.getKey() / layerCells]];
      BlockEntityType<?> type = BlockEdits.blockEntityType(palette.states()[entryIndex]);
      if (palette.keepsBlockEntity()[entryIndex] && type != null) {
        kept.put(entry.getKey(), blockEntities.fix(entry.getValue(), type));
      }
    }
    return LitematicWriter.write(
        new LitematicWriter.Metadata(name, author, "", timeMillis, current),
        LitematicWriter.Contents.ofBands(
            volume.sizeX(),
            volume.sizeY(),
            volume.sizeZ(),
            statesByBand,
            volume.cells(),
            edited.bandOfY(),
            kept));
  }

  /** A band's palette as a converted file writes it, in the running game's names. */
  private static List<String> convertedStates(
      VoxelVolume volume, LoadedFile.EditedPalette edited, int band) {
    PreviewPalette palette = edited.palettes()[band];
    List<String> states = new ArrayList<>(volume.palette().size());
    for (int index = 0; index < volume.palette().size(); index++) {
      BlockEdits.Edit edit = edited.edits()[band][index];
      if (edit != null) {
        states.add(edit.text());
      } else if (palette.known()[index]) {
        states.add(BlockEdits.text(palette.states()[index]));
      } else {
        // A block the game does not know stays as named: Litematica shows it as air too.
        states.add(volume.palette().get(index));
      }
    }
    return states;
  }

  /** Upgrades block-entity NBT with the game's data fixer, as the preview does to draw it. */
  private record BlockEntityFixer(@Nullable DataFixer fixer, int from, int to) {
    BlockEntityFixer(int from, int to) {
      this(from > 0 && from < to ? DataFixers.getDataFixer() : null, from, to);
    }

    /** The NBT in the current form, its id the block's own type (old files name it "Chest"). */
    NbtTag.CompoundTag fix(NbtTag.CompoundTag saved, BlockEntityType<?> type) {
      CompoundTag tag = VanillaNbt.compound(saved);
      Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
      if (id != null) {
        tag.putString(ID, id.toString());
      }
      DataFixer active = fixer;
      if (active != null) {
        try {
          Tag fixed =
              active
                  .update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, tag), from, to)
                  .getValue();
          if (fixed instanceof CompoundTag fixedCompound) {
            tag = fixedCompound;
          }
        } catch (RuntimeException exception) {
          // NBT an old version never wrote: kept as saved, as the preview loads it.
          return saved;
        }
      }
      return VanillaNbt.core(tag);
    }
  }
}
