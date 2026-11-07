package com.einsteinmods.schematicvault.core.edit;

import org.jspecify.annotations.Nullable;

/**
 * What each block state of a file becomes. The client answers with the game's own blocks (the
 * file's states as the game names them, the replacements, the new blocks' properties); tests with a
 * map. Called once per palette entry, never per cell.
 */
@FunctionalInterface
public interface StateMapper {
  /**
   * The change for {@code state}, as the file writes it ({@code BlockStateString} text), or null
   * when it stays as it is.
   */
  @Nullable StateChange change(String state);
}
