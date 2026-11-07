package com.einsteinmods.schematicvault.client.litematica;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The way from {@code LitematicaMainMenuMixin} to {@link LitematicaIntegration}, with no Litematica
 * type: the guard stands outside the integration because a {@link LinkageError} can come from
 * linking that class itself, where its own methods could not catch it. A failure removes the button
 * for the rest of the game, with one warning line; the menu works as without this mod. The state is
 * static because Litematica's menu outlives any vault session. Render thread only.
 */
public final class LitematicaMenuButton {
  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicaMenuButton.class);

  /** Set once the button or the bridge failed: the menu no longer gets the button. */
  private static boolean disabled;

  private LitematicaMenuButton() {}

  /**
   * Adds the button to {@code menu}, Litematica's main menu, unless a call to Litematica failed.
   *
   * @param menu the menu, as {@code Object} because the mixin knows no Litematica type
   */
  public static void add(Object menu) {
    if (disabled) {
      return;
    }
    try {
      LitematicaIntegration.addVaultButton(menu);
    } catch (RuntimeException | LinkageError failure) {
      disabled = true;
      LOGGER.warn("Schematic Vault button left out of Litematica's menu: {}", failure.toString());
    }
  }

  /** Called when the bridge turns itself off: this Litematica is not compatible. */
  static void disable() {
    disabled = true;
  }
}
