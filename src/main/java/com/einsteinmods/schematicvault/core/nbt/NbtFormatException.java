package com.einsteinmods.schematicvault.core.nbt;

/** The bytes are not a well-formed NBT file within the reader's limits. */
public final class NbtFormatException extends Exception {
  public NbtFormatException(String message) {
    super(message);
  }

  public NbtFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
