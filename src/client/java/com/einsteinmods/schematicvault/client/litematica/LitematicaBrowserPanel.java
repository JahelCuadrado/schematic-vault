package com.einsteinmods.schematicvault.client.litematica;

import com.einsteinmods.schematicvault.client.screen.BrowserPreview;
import com.einsteinmods.schematicvault.client.screen.BrowserThumbnails;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The way from the mixins on Litematica's schematic browser to {@link LitematicaIntegration}, with
 * no Litematica type: the guard stands outside the integration because a {@link LinkageError} can
 * come from linking that class itself. It also owns the browser's {@link BrowserPreview}: made when
 * "Load schematics" builds its buttons, closed on the first tick after the player left that screen,
 * whichever way (no hook sees them all: its own buttons, Escape, a disconnect). A failure leaves
 * the browser as it is without this mod for the rest of the game, with one warning line. Static
 * because Litematica's screens know nothing of the mod. Render thread only.
 */
public final class LitematicaBrowserPanel {
  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicaBrowserPanel.class);

  /** Set once a call failed: the browser no longer gets the preview. */
  private static boolean disabled;

  /** Set by the row mixin's first call: without it no thumbnail can show, so none is offered. */
  private static boolean rowHookWorks;

  private static @Nullable BrowserPreview preview;

  private LitematicaBrowserPanel() {}

  /**
   * The browser has just built its buttons, which it does again on every selection: the panel's
   * button goes back among them.
   *
   * @param screen the browser, as {@code Object} because the mixin knows no Litematica type
   */
  public static void addButton(Object screen) {
    if (disabled || !(screen instanceof Screen browser)) {
      return;
    }
    try {
      BrowserPreview shown = preview;
      if (shown == null || shown.browser() != browser) {
        close();
        shown = new BrowserPreview(Minecraft.getInstance(), browser);
        preview = shown;
      }
      LitematicaIntegration.keepMainMenuClear(screen);
      LitematicaIntegration.addPanelButton(screen, shown);
      LitematicaIntegration.addThumbnailButton(screen, shown.thumbnails());
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /**
   * Litematica has just drawn the info panel of the browser; another screen's panel (saving a
   * schematic) gets nothing.
   *
   * @param list the browser's list, as {@code Object} because the mixin knows no Litematica type
   * @param entry the selected entry, as {@code Object} too
   * @param x the panel's {@code x} after drawing
   * @param y the panel's {@code y} after drawing: where its data ends
   * @param height the panel's height
   */
  public static void draw(
      Object list,
      GuiGraphicsExtractor graphics,
      @Nullable Object entry,
      int x,
      int y,
      int height) {
    if (disabled) {
      return;
    }
    BrowserPreview shown = preview;
    if (shown == null || shown.browser() != Minecraft.getInstance().gui.screen()) {
      LitematicaRowImages.fitRows(list);
      return;
    }
    try {
      LitematicaIntegration.drawPanel(graphics, entry, x, y, height, shown);
      shown.thumbnails().extract(graphics);
      LitematicaIntegration.fitRows(list, shown.thumbnails().rows());
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /**
   * Litematica has just drawn a row's icon in a MaLiLib file list. {@link #preview} exists for no
   * screen but the browser, whose rows get their thumbnail here, and the shown screen is compared,
   * as {@link #draw} does, for the tick between leaving the browser and {@link #onClientTick}. Any
   * other list goes to {@link LitematicaRowImages}. Cost here, see {@link
   * com.einsteinmods.schematicvault.client.cache.RowThumbnails}.
   *
   * @param row the row, as {@code Object} because the mixin knows no MaLiLib type
   * @param selected whether Litematica has just outlined the row as selected
   */
  public static void drawRow(GuiGraphicsExtractor graphics, Object row, boolean selected) {
    rowHookWorks = true;
    if (disabled) {
      return;
    }
    BrowserPreview shown = preview;
    if (shown == null || shown.browser() != Minecraft.getInstance().gui.screen()) {
      LitematicaRowImages.drawFileRow(graphics, row, selected);
      return;
    }
    if (!shown.thumbnails().showsRows()) {
      return;
    }
    try {
      LitematicaIntegration.drawRowThumbnail(graphics, row, selected, shown.thumbnails().rows());
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /**
   * Where a row of a MaLiLib file list draws its name: past the image in taller rows, else at
   * {@code x}, Litematica's own. No Litematica type is touched: other lists link nothing.
   */
  public static int rowTextX(int x) {
    BrowserPreview shown = preview;
    boolean tall =
        shown != null ? shown.thumbnails().rows().tallRows() : LitematicaRowImages.tallRows();
    return tall ? x + BrowserThumbnails.ROW_TEXT_SHIFT : x;
  }

  /** Whether the rows are reached: the row mixin applied and a row has been drawn. */
  static boolean rowHookWorks() {
    return rowHookWorks;
  }

  /**
   * Closes the preview once the player left the browser, and stops thumbnails as soon as it is not
   * on screen. A few field reads while there is no preview.
   */
  public static void onClientTick(Minecraft minecraft) {
    LitematicaRowImages.onClientTick(minecraft);
    BrowserPreview shown = preview;
    if (shown == null) {
      return;
    }
    Screen current = minecraft.gui.screen();
    if (!shown.stillShown(current)) {
      close();
    } else if (current != shown.browser()) {
      shown.thumbnails().stop();
    }
  }

  /** Called when the bridge turns itself off: this Litematica is not compatible. */
  static void disable() {
    disabled = true;
  }

  private static void close() {
    BrowserPreview shown = preview;
    if (shown != null) {
      preview = null;
      shown.close();
    }
  }

  private static void fail(Throwable failure) {
    disabled = true;
    close();
    LOGGER.warn("Schematic Vault left out of Litematica's browser: {}", failure.toString());
  }
}
