package com.einsteinmods.schematicvault.client.litematica;

import com.einsteinmods.schematicvault.client.VaultSession;
import com.einsteinmods.schematicvault.client.cache.LocalThumbnailStore;
import com.einsteinmods.schematicvault.client.cache.RowThumbnails;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thumbnails already generated, in Litematica's lists other than "Load schematics" (which has its
 * own, {@link LitematicaBrowserPanel}): "Loaded schematics", "Schematic placements", the schematic
 * manager and the save screens. Nothing is generated here. Lives while such a screen is shown: the
 * first row drawn on a screen makes the textures' owner, if the thumbnails folder exists (one disk
 * check per screen opened), and the first tick on another screen frees it. With no such folder each
 * row costs a screen comparison. With it, one identity hash per row drawn, no allocation, see
 * {@link RowThumbnails}. A failure leaves these lists as they are without this mod for the rest of
 * the game, with one warning line. Static because Litematica's screens know nothing of the mod.
 * Render thread only.
 */
public final class LitematicaRowImages {
  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicaRowImages.class);

  /** Past the image: {@code WidgetSchematicEntry#render} starts a name at the image's end. */
  private static final int NAME_SHIFT = 5;

  /** Set once a call failed: the lists no longer get thumbnails. */
  private static boolean disabled;

  /** The screen {@link #rows} belongs to, or the last one looked at if it got none. */
  private static @Nullable Screen screen;

  private static @Nullable RowThumbnails rows;

  private LitematicaRowImages() {}

  /**
   * Litematica has just drawn a row of its loaded schematics or placements.
   *
   * @param row the row, as {@code Object} because the mixin knows no Litematica type
   */
  public static void drawSchematicRow(GuiGraphicsExtractor graphics, Object row) {
    RowThumbnails shown = rowsOnScreen();
    if (shown == null) {
      return;
    }
    try {
      LitematicaIntegration.drawSchematicRowThumbnail(graphics, row, shown);
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /**
   * Litematica has just drawn a row's icon in a MaLiLib file list other than "Load schematics".
   * Lists that do not show schematics get nothing: they cost a screen comparison per row.
   */
  static void drawFileRow(GuiGraphicsExtractor graphics, Object row, boolean selected) {
    RowThumbnails shown = rowsOnScreen();
    if (shown == null) {
      return;
    }
    try {
      LitematicaIntegration.drawRowThumbnail(graphics, row, selected, shown);
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /** The list of a file browser walked its rows: it follows them, as in "Load schematics". */
  static void fitRows(Object list) {
    RowThumbnails shown = rows;
    if (shown == null || screen != Minecraft.getInstance().gui.screen()) {
      return;
    }
    try {
      LitematicaIntegration.fitRows(list, shown);
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  /** Where a loaded schematic or a placement draws its name: {@code x} until an image shows. */
  public static int nameX(int x) {
    RowThumbnails shown = rows;
    return shown != null && shown.imageDrawn() ? x + NAME_SHIFT : x;
  }

  /** Whether the file list on screen has the taller rows. */
  static boolean tallRows() {
    RowThumbnails shown = rows;
    return shown != null && shown.tallRows();
  }

  /** Frees the textures once their screen is gone. One field read on any other screen. */
  static void onClientTick(Minecraft minecraft) {
    if (screen == null) {
      return;
    }
    if (minecraft.gui.screen() != screen) {
      close();
    } else if (rows != null) {
      rows.nextFrame();
    }
  }

  /** The thumbnails of the screen shown, looked for once per screen; null if it gets none. */
  private static @Nullable RowThumbnails rowsOnScreen() {
    Minecraft minecraft = Minecraft.getInstance();
    Screen current = minecraft.gui.screen();
    if (current != screen) {
      close();
      screen = current;
      open(minecraft, current);
    }
    return rows;
  }

  private static void open(Minecraft minecraft, @Nullable Screen current) {
    if (disabled
        || current == null
        || !FabricLoader.getInstance().isModLoaded(LitematicaBridge.MOD_ID)) {
      return;
    }
    try {
      if (!LitematicaIntegration.listsSchematics(current)) {
        return;
      }
      LocalThumbnailStore store = new LocalThumbnailStore(VaultSession.cacheDirectory());
      if (store.exists()) {
        rows = new RowThumbnails(store, minecraft.getTextureManager(), minecraft);
      }
    } catch (RuntimeException | LinkageError failure) {
      fail(failure);
    }
  }

  private static void close() {
    RowThumbnails shown = rows;
    rows = null;
    screen = null;
    if (shown != null) {
      shown.release();
    }
  }

  private static void fail(Throwable failure) {
    disabled = true;
    close();
    LOGGER.warn("Schematic Vault left out of Litematica's lists: {}", failure.toString());
  }
}
