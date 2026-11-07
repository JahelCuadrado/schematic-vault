package com.einsteinmods.schematicvault.core.files;

import java.util.Optional;

/**
 * File formats the mod saves. Each has the API's {@code format} value (schema {@code FileFormat})
 * and the saved file's extension, which differ for every format but Litematica. All four are what
 * Litematica lists from its schematics folder; any other format is never saved.
 */
public enum SchematicFormat {
  LITEMATIC("litematic", "litematic", "Litematica"),
  SCHEM("sponge_schem", "schem", "Sponge"),
  SCHEMATIC("mcedit_schematic", "schematic", "MCEdit"),
  NBT("structure_nbt", "nbt", "Structure");

  private static final char EXTENSION_DOT = '.';

  private final String id;
  private final String extension;
  private final String label;

  SchematicFormat(String id, String extensionWithoutDot, String label) {
    this.id = id;
    this.extension = "." + extensionWithoutDot;
    this.label = label;
  }

  /** The API's {@code format} value, e.g. {@code sponge_schem}. Never a file extension. */
  public String id() {
    return id;
  }

  /** Short name players know the format by. */
  public String label() {
    return label;
  }

  /** The saved file's extension with its dot, e.g. {@code .schem}. */
  public String extension() {
    return extension;
  }

  /** The format of an API {@code format} value. */
  public static Optional<SchematicFormat> fromId(String id) {
    for (SchematicFormat format : values()) {
      if (format.id.equals(id)) {
        return Optional.of(format);
      }
    }
    return Optional.empty();
  }

  /** The format of a file extension without its dot, e.g. {@code schem} (case-sensitive). */
  public static Optional<SchematicFormat> fromExtension(String extensionWithoutDot) {
    for (SchematicFormat format : values()) {
      if (format.extension.length() == extensionWithoutDot.length() + 1
          && format.extension.endsWith(extensionWithoutDot)) {
        return Optional.of(format);
      }
    }
    return Optional.empty();
  }

  /** The format of a file by its name's extension (case-sensitive); empty for any other file. */
  public static Optional<SchematicFormat> fromFileName(String fileName) {
    int dot = fileName.lastIndexOf(EXTENSION_DOT);
    return dot < 0 ? Optional.empty() : fromExtension(fileName.substring(dot + 1));
  }
}
