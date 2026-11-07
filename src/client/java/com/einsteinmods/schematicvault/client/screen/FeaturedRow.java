package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.core.catalog.CatalogIndex;
import com.einsteinmods.schematicvault.core.catalog.Difficulty;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The featured designs above the list ({@link CatalogIndex#featured}): one card each with a large
 * thumbnail, the shown title, the difficulty and a star. Clicking a card selects its design.
 *
 * <p>Cost: laid out when the featured designs or the width change (texts fitted then); a frame
 * draws at most three cards, allocating nothing of the mod's own. Thumbnails come from the same
 * cache as the list's.
 */
final class FeaturedRow extends AbstractWidget {
  private static final int CARD_GAP = 4;
  private static final int PADDING = 3;
  private static final int LINE_GAP = 1;
  private static final int MIN_THUMBNAIL_SIZE = 24;
  private static final int MAX_THUMBNAIL_SIZE = 64;

  /** Real pixels of the API's PNG: a card never draws it on more, so it never looks blurred. */
  private static final int THUMBNAIL_PIXELS = 96;

  private static final String ELLIPSIS = "...";

  /** "★", the featured badge. */
  private static final String STAR = "\u2605";

  private static final String NARRATION_SEPARATOR = ", ";

  private static final int CARD_COLOR = 0xFF26262C;
  private static final int CARD_HOVER_COLOR = 0xFF3A3A44;
  private static final int BORDER_COLOR = 0xFF505058;
  private static final int SELECTED_BORDER_COLOR = 0xFFFFFFFF;
  private static final int TITLE_COLOR = 0xFFFFFFFF;
  private static final int STAR_COLOR = 0xFFFFD040;
  private static final int EASY_COLOR = 0xFF80E080;
  private static final int MEDIUM_COLOR = 0xFFE0D060;
  private static final int HARD_COLOR = 0xFFFF8060;

  private final Font font;
  private final ThumbnailCache thumbnails;
  private final CatalogIndex index;
  private final IntConsumer onSelect;
  private final int thumbnailSize;
  private final int cardWidth;
  private final int starWidth;
  private Card[] cards = new Card[0];
  private @Nullable String selectedId;
  private Component narration = Component.empty();
  private int titleTop;

  FeaturedRow(
      int left,
      int top,
      int width,
      Font font,
      ThumbnailCache thumbnails,
      CatalogIndex index,
      IntConsumer onSelect) {
    super(left, top, width, height(width, font), Component.empty());
    this.font = font;
    this.thumbnails = thumbnails;
    this.index = index;
    this.onSelect = onSelect;
    this.cardWidth = cardWidth(width);
    this.thumbnailSize = thumbnailSize(width);
    this.starWidth = font.width(STAR);
  }

  /** The row's height in a list of {@code width}. */
  static int height(int width, Font font) {
    return PADDING * 2 + thumbnailSize(width) + LINE_GAP * 2 + font.lineHeight * 2;
  }

  private static int cardWidth(int width) {
    return (width - CARD_GAP * (CatalogIndex.FEATURED_COUNT - 1)) / CatalogIndex.FEATURED_COUNT;
  }

  /** The card's fit, but never more GUI units than the image has real pixels at this GUI scale. */
  private static int thumbnailSize(int width) {
    int fit = Math.clamp(cardWidth(width) - PADDING * 2, MIN_THUMBNAIL_SIZE, MAX_THUMBNAIL_SIZE);
    return Math.min(fit, THUMBNAIL_PIXELS / Minecraft.getInstance().getWindow().getGuiScale());
  }

  /** Shows the designs at {@code featured} (catalog indices). */
  void show(int[] featured, @Nullable String selected) {
    selectedId = selected;
    int textWidth = cardWidth - PADDING * 2;
    cards = new Card[featured.length];
    List<String> narrated = new ArrayList<>(featured.length);
    int textTop = getY() + PADDING + thumbnailSize + LINE_GAP;
    for (int position = 0; position < featured.length; position++) {
      int catalogIndex = featured[position];
      SchematicSummary schematic = index.get(catalogIndex);
      int left = getX() + position * (cardWidth + CARD_GAP);
      String title = fit(index.title(catalogIndex), textWidth);
      Difficulty difficulty = schematic.difficulty();
      String label = difficulty == null ? "" : FilterBar.difficultyName(difficulty).getString();
      cards[position] =
          new Card(
              catalogIndex,
              schematic.id(),
              schematic.thumbnailPngUrl(),
              left,
              title,
              left + (cardWidth - font.width(title)) / 2,
              label,
              left + (cardWidth - font.width(label)) / 2,
              difficulty == null ? TITLE_COLOR : color(difficulty),
              left + (cardWidth - thumbnailSize) / 2);
      narrated.add(
          Component.translatable("schematicvault.featured.card", label, index.title(catalogIndex))
              .getString());
    }
    narration = Component.literal(String.join(NARRATION_SEPARATOR, narrated));
    titleTop = textTop;
  }

  void select(@Nullable String id) {
    selectedId = id;
  }

  private static int color(Difficulty difficulty) {
    return switch (difficulty) {
      case EASY -> EASY_COLOR;
      case MEDIUM -> MEDIUM_COLOR;
      case HARD -> HARD_COLOR;
    };
  }

  private String fit(String text, int maxWidth) {
    if (font.width(text) <= maxWidth) {
      return text;
    }
    return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS;
  }

  @Override
  protected void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    int top = getY();
    int bottom = top + getHeight();
    for (Card card : cards) {
      boolean hovered = card.contains(mouseX, mouseY, top, bottom, cardWidth);
      graphics.fill(
          card.left, top, card.left + cardWidth, bottom, hovered ? CARD_HOVER_COLOR : CARD_COLOR);
      graphics.outline(
          card.left,
          top,
          cardWidth,
          getHeight(),
          card.id.equals(selectedId) ? SELECTED_BORDER_COLOR : BORDER_COLOR);
      Thumbnails.draw(
          graphics,
          thumbnails,
          card.thumbnailUrl,
          card.thumbnailLeft,
          top + PADDING,
          thumbnailSize);
      graphics.text(
          font, STAR, card.left + cardWidth - PADDING - starWidth, top + PADDING, STAR_COLOR);
      graphics.text(font, card.title, card.titleLeft, titleTop, TITLE_COLOR);
      graphics.text(
          font,
          card.difficulty,
          card.difficultyLeft,
          titleTop + font.lineHeight + LINE_GAP,
          card.difficultyColor);
      if (hovered) {
        graphics.requestCursor(CursorTypes.POINTING_HAND);
      }
    }
  }

  /** Only over a card: the gaps and the slots of missing cards let clicks through. */
  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    return cardAt(mouseX, mouseY) != null;
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    Card card = cardAt(event.x(), event.y());
    if (card != null) {
      onSelect.accept(card.catalogIndex);
    }
  }

  /** Clicking a card selects a design; keyboard focus stays where it was (the search box). */
  @Override
  public boolean shouldTakeFocusAfterInteraction() {
    return false;
  }

  private @Nullable Card cardAt(double mouseX, double mouseY) {
    int top = getY();
    int bottom = top + getHeight();
    for (Card card : cards) {
      if (card.contains(mouseX, mouseY, top, bottom, cardWidth)) {
        return card;
      }
    }
    return null;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, narration);
  }

  /** One card, its texts fitted and placed once. */
  private record Card(
      int catalogIndex,
      String id,
      @Nullable String thumbnailUrl,
      int left,
      String title,
      int titleLeft,
      String difficulty,
      int difficultyLeft,
      int difficultyColor,
      int thumbnailLeft) {
    boolean contains(double mouseX, double mouseY, int top, int bottom, int width) {
      return mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < bottom;
    }
  }
}
