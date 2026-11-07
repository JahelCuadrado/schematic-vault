package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.volume.SpongeReader;
import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;

/**
 * Applies block replacements to a design file in its own format. Replacements of the whole design
 * change only its palette (and what depends on it: the merged block indices, block-entity data of
 * blocks that changed type, Litematica's block count). Replacements limited to some layers ({@link
 * LayerChanges}) add the new states to the palette and rewrite the cells of those layers, leaving
 * every other cell and palette entry as the file has them. A legacy MCEdit {@code .schematic} is
 * never edited: it is converted ({@link LitematicWriter}) and saved as {@code .litematic}; a Sponge
 * schematic saved with that extension is rewritten as one and saved as {@code .schem}.
 */
public final class SchematicRewriter {
  private SchematicRewriter() {}

  /** The format the edited file is saved in. */
  public static SchematicFormat savedFormat(SchematicFormat format, CompoundTag root) {
    if (format != SchematicFormat.SCHEMATIC) {
      return format;
    }
    return SpongeReader.isSponge(root) ? SchematicFormat.SCHEM : SchematicFormat.LITEMATIC;
  }

  /**
   * The file's tree with its palette changed as {@code mapper} says; the same tree when nothing
   * changes.
   *
   * @throws VolumeFormatException the file is damaged, or a legacy MCEdit file (convert it instead)
   */
  public static CompoundTag rewrite(SchematicFormat format, CompoundTag root, StateMapper mapper)
      throws VolumeFormatException {
    return switch (savedFormat(format, root)) {
      case LITEMATIC ->
          format == SchematicFormat.SCHEMATIC ? legacy() : LitematicRewrite.rewrite(root, mapper);
      case SCHEM -> SpongeRewrite.rewrite(root, mapper);
      case NBT -> StructureRewrite.rewrite(root, mapper);
      case SCHEMATIC -> legacy();
    };
  }

  /**
   * The file's tree with its cells changed layer by layer as {@code changes} say; the same tree
   * when nothing changes.
   *
   * @throws VolumeFormatException the file is damaged, or a legacy MCEdit file (convert it instead)
   */
  public static CompoundTag rewrite(SchematicFormat format, CompoundTag root, LayerChanges changes)
      throws VolumeFormatException {
    return switch (savedFormat(format, root)) {
      case LITEMATIC ->
          format == SchematicFormat.SCHEMATIC
              ? legacy()
              : LitematicRewrite.rewriteLayers(root, changes);
      case SCHEM -> SpongeRewrite.rewriteLayers(root, changes);
      case NBT -> StructureRewrite.rewriteLayers(root, changes);
      case SCHEMATIC -> legacy();
    };
  }

  private static CompoundTag legacy() throws VolumeFormatException {
    throw VolumeFormatException.unreadable("a legacy MCEdit file is converted, never rewritten");
  }
}
