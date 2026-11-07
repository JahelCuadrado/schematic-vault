package com.einsteinmods.schematicvault.core.volume;

/**
 * Which game version a file's block states are read as. Pure.
 *
 * <p>A file without a data version (0, what the readers give when it is absent, or 1, a placeholder
 * some tools write) is read as Minecraft 1.12.2: every such file in the API holds pre-1.13 names,
 * or modern names no 1.12 state equals, which the game's fixers leave alone. A negative version
 * stays as it is, with no fixing: the 22 {@code .schem} files in the API with -1 hold modern
 * blocks, and reading them as 1.12 turns {@code stone_slab} and {@code snow} written without
 * properties into other blocks. Their few old names go through {@link BlockRenames}.
 */
public final class DataVersions {
  /** Minecraft 1.12.2, the last version before the flattening. */
  public static final int UNVERSIONED = 1343;

  private static final int ABSENT = 0;
  private static final int PLACEHOLDER = 1;

  private DataVersions() {}

  /** Whether a file has no data version, so its states are read as 1.12.2's. */
  public static boolean isUnversioned(int dataVersion) {
    return dataVersion == ABSENT || dataVersion == PLACEHOLDER;
  }

  /** The data version a file's block states are fixed from. */
  public static int forFixer(int dataVersion) {
    return isUnversioned(dataVersion) ? UNVERSIONED : dataVersion;
  }
}
