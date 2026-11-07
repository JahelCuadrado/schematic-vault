package com.einsteinmods.schematicvault.core.volume;

import org.jspecify.annotations.Nullable;

/** A design file that cannot be read into a {@link VoxelVolume}: damaged, unknown, or too big. */
public final class VolumeFormatException extends Exception {
  private final boolean tooLarge;

  private VolumeFormatException(String message, boolean tooLarge, @Nullable Throwable cause) {
    super(message, cause);
    this.tooLarge = tooLarge;
  }

  /** The file is damaged or not the format it claims. */
  public static VolumeFormatException unreadable(String message) {
    return new VolumeFormatException(message, false, null);
  }

  public static VolumeFormatException unreadable(String message, @Nullable Throwable cause) {
    return new VolumeFormatException(message, false, cause);
  }

  /** The file is fine but its volume is past {@link VoxelVolume#MAX_CELLS}. */
  public static VolumeFormatException tooLarge(String message) {
    return new VolumeFormatException(message, true, null);
  }

  public boolean tooLarge() {
    return tooLarge;
  }
}
