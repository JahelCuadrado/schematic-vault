package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Draws a design's thumbnail into a square, fitted and centred, or a placeholder (a structure block
 * on a dark square) while it loads or when the API has none. No allocation per call.
 */
final class Thumbnails {
  private static final int BACKGROUND = 0xFF1E1E24;
  private static final int BORDER = 0xFF3A3A44;
  private static final int ITEM_SIZE = 16;

  /** Created when the vault first draws a placeholder, never per frame. */
  private static final ItemStack PLACEHOLDER = new ItemStack(Items.STRUCTURE_BLOCK);

  private Thumbnails() {}

  static void draw(
      GuiGraphicsExtractor graphics,
      ThumbnailCache cache,
      @Nullable String url,
      int x,
      int y,
      int size) {
    ThumbnailCache.Thumbnail thumbnail = url == null ? null : cache.texture(url);
    graphics.fill(x, y, x + size, y + size, BACKGROUND);
    if (thumbnail == null) {
      graphics.outline(x, y, size, size, BORDER);
      if (size >= ITEM_SIZE) {
        graphics.item(PLACEHOLDER, x + (size - ITEM_SIZE) / 2, y + (size - ITEM_SIZE) / 2);
      }
      return;
    }
    int longest = Math.max(1, Math.max(thumbnail.width(), thumbnail.height()));
    int width = Math.max(1, thumbnail.width() * size / longest);
    int height = Math.max(1, thumbnail.height() * size / longest);
    int left = x + (size - width) / 2;
    int top = y + (size - height) / 2;
    graphics.blit(
        thumbnail.texture(), left, top, left + width, top + height, 0.0F, 1.0F, 0.0F, 1.0F);
  }
}
