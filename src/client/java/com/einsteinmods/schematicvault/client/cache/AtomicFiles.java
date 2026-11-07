package com.einsteinmods.schematicvault.client.cache;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes through a temporary file in the same folder, then one atomic move: a crash or a full disk
 * leaves the old file (or none), never a half-written one.
 */
public final class AtomicFiles {
  private static final String TEMP_PREFIX = ".schematicvault-";
  private static final String TEMP_SUFFIX = ".tmp";

  private AtomicFiles() {}

  /** A new empty temporary file next to where {@code target} will go. */
  public static Path createTemp(Path target) throws IOException {
    Path folder = target.toAbsolutePath().getParent();
    Files.createDirectories(folder);
    return Files.createTempFile(folder, TEMP_PREFIX, TEMP_SUFFIX);
  }

  /** Replaces {@code target} with {@code bytes}. */
  public static void write(Path target, byte[] bytes) throws IOException {
    Path temp = createTemp(target);
    try {
      Files.write(temp, bytes);
      moveInto(temp, target);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Moves a finished temporary file onto {@code target}, atomically where the disk allows it. */
  public static void moveInto(Path temp, Path target) throws IOException {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
