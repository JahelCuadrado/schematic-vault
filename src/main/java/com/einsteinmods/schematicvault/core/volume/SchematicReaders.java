package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.nbt.NbtFormatException;
import com.einsteinmods.schematicvault.core.nbt.NbtReader;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import java.io.IOException;

/** Reads a design file of any of the four formats into a {@link VoxelVolume}. */
public final class SchematicReaders {
  private SchematicReaders() {}

  /** Supplies the legacy table only when an MCEdit file needs it. */
  @FunctionalInterface
  public interface LegacyTable {
    LegacyBlocks get() throws IOException;
  }

  public static VoxelVolume read(SchematicFormat format, byte[] file, LegacyTable legacy)
      throws VolumeFormatException {
    CompoundTag root;
    try {
      root = NbtReader.read(file);
    } catch (NbtFormatException exception) {
      throw VolumeFormatException.unreadable(exception.getMessage(), exception);
    }
    return switch (format) {
      case LITEMATIC -> LitematicReader.read(root);
      case SCHEM -> SpongeReader.read(root);
      case SCHEMATIC -> McEditReader.read(root, legacyTable(legacy));
      case NBT -> StructureReader.read(root);
    };
  }

  private static LegacyBlocks legacyTable(LegacyTable legacy) throws VolumeFormatException {
    try {
      return legacy.get();
    } catch (IOException exception) {
      throw VolumeFormatException.unreadable("legacy block table unavailable", exception);
    }
  }
}
