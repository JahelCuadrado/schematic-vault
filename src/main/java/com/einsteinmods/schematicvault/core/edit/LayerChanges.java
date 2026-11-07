package com.einsteinmods.schematicvault.core.edit;

import org.jspecify.annotations.Nullable;

/**
 * What each block state of a file becomes layer by layer: a state's change depends on the band of
 * its Y ({@link RuleBands}), counted in the volume's layers.
 *
 * @param bandOfY the band of each Y layer of the volume; shared, never written
 * @param bands how many bands there are
 * @param mapper the change per band
 */
public record LayerChanges(int[] bandOfY, int bands, BandMapper mapper) {
  /**
   * What the block states of a file become on one band. The client answers with the game's own
   * blocks; tests with a map. Called at most once per band and palette entry, never per cell.
   */
  @FunctionalInterface
  public interface BandMapper {
    /**
     * The change for {@code state}, as the file writes it, on {@code band}; null when it stays as
     * it is.
     */
    @Nullable StateChange change(int band, String state);
  }
}
