package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.core.catalog.CatalogFilter;
import com.einsteinmods.schematicvault.core.catalog.CategoryRow;
import com.einsteinmods.schematicvault.core.catalog.CategoryTree;
import com.einsteinmods.schematicvault.core.catalog.Difficulty;
import com.einsteinmods.schematicvault.core.catalog.SizeBucket;
import com.einsteinmods.schematicvault.core.catalog.WidthBucket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The active filters below the bar, one chip each (category, difficulty, "verified only", size,
 * width, search text) with a "×" that removes it, and "Clear all" when two or more are on. Chips
 * flow left to right and wrap; each holds the filter that removing it gives, so a click is one
 * change.
 *
 * <p>Cost: laid out when the filter or the width changes (texts fitted then); a frame fills and
 * writes the chips, allocating nothing of the mod's own.
 */
final class FilterChips extends AbstractWidget {
  static final int CHIP_HEIGHT = 12;

  private static final int ROW_GAP = 2;
  private static final int CHIP_GAP = 3;
  private static final int PADDING = 4;

  /** A chip is never wider than this share of the row: seven chips fit in three rows. */
  private static final int CHIPS_PER_ROW = 3;

  private static final String REMOVE = " ×";
  private static final String ELLIPSIS = "...";
  private static final String VALUE_SEPARATOR = ", ";

  private static final int CHIP_COLOR = 0xFF303030;
  private static final int CHIP_HOVER_COLOR = 0xFF505050;
  private static final int BORDER_COLOR = 0xFF707070;
  private static final int TEXT_COLOR = 0xFFE0E0E0;
  private static final int REMOVE_COLOR = 0xFFA0A0A0;
  private static final int REMOVE_HOVER_COLOR = 0xFFFF6060;

  private static final Component CLEAR_ALL =
      Component.translatable("schematicvault.chip.clear_all");

  private final Font font;
  private final Consumer<CatalogFilter> onChange;
  private final int removeWidth;
  private Chip[] chips = new Chip[0];
  private Component narration = Component.empty();

  FilterChips(Font font, Consumer<CatalogFilter> onChange) {
    super(0, 0, 0, 0, Component.empty());
    this.font = font;
    this.onChange = onChange;
    this.removeWidth = font.width(REMOVE);
  }

  /**
   * Lays the chips of {@code filter} out from ({@code left}, {@code top}) in rows of {@code width}.
   * Its height is 0 when no filter is on.
   *
   * @param categories the category rows, for the chosen category's shown name
   */
  void layout(int left, int top, int width, CatalogFilter filter, List<CategoryRow> categories) {
    setX(left);
    setY(top);
    setWidth(width);
    List<String> labels = new ArrayList<>();
    List<CatalogFilter> removed = new ArrayList<>();
    if (!filter.categoryPath().isEmpty()) {
      labels.add(
          CategoryTree.find(categories, filter.categoryPath())
              .map(CategoryRow::name)
              .orElse(filter.categoryPath()));
      removed.add(filter.withCategory(""));
    }
    if (!filter.difficulties().isEmpty()) {
      List<String> names = new ArrayList<>();
      for (Difficulty difficulty : Difficulty.values()) {
        if (filter.difficulties().contains(difficulty)) {
          names.add(FilterBar.difficultyName(difficulty).getString());
        }
      }
      labels.add(group(FilterBar.DIFFICULTY, names));
      removed.add(filter.withDifficulties(Set.of()));
    }
    if (filter.verifiedOnly()) {
      labels.add(FilterBar.VERIFIED.getString());
      removed.add(filter.withVerifiedOnly(false));
    }
    if (!filter.sizes().isEmpty()) {
      List<String> names = new ArrayList<>();
      for (SizeBucket size : SizeBucket.values()) {
        if (filter.sizes().contains(size)) {
          names.add(FilterBar.sizeName(size).getString());
        }
      }
      labels.add(group(FilterBar.SIZE, names));
      removed.add(filter.withSizes(Set.of()));
    }
    if (!filter.widths().isEmpty()) {
      List<String> names = new ArrayList<>();
      for (WidthBucket bucket : WidthBucket.values()) {
        if (filter.widths().contains(bucket)) {
          names.add(bucket.label());
        }
      }
      labels.add(group(FilterBar.WIDTH, names));
      removed.add(filter.withWidths(Set.of()));
    }
    if (!filter.text().isBlank()) {
      labels.add(
          Component.translatable("schematicvault.chip.search", filter.text().strip()).getString());
      removed.add(filter.withText(""));
    }
    if (labels.size() >= 2) {
      labels.add(CLEAR_ALL.getString());
      removed.add(filter.cleared());
    }
    int maxChipWidth = Math.max(removeWidth + PADDING * 2, (width - CHIP_GAP * 2) / CHIPS_PER_ROW);
    chips = new Chip[labels.size()];
    int chipX = left;
    int chipY = top;
    for (int index = 0; index < chips.length; index++) {
      String label = fit(labels.get(index), maxChipWidth - PADDING * 2 - removeWidth);
      int chipWidth = font.width(label) + removeWidth + PADDING * 2;
      if (chipX > left && chipX + chipWidth > left + width) {
        chipX = left;
        chipY += CHIP_HEIGHT + ROW_GAP;
      }
      chips[index] = new Chip(label, chipX, chipY, chipWidth, removed.get(index));
      chipX += chipWidth + CHIP_GAP;
    }
    setHeight(chips.length == 0 ? 0 : chipY + CHIP_HEIGHT - top);
    narration = Component.literal(String.join(VALUE_SEPARATOR, labels));
  }

  /** {@code name: value, value}, as a chip and a collapsed "Filters" section show a group. */
  static String group(Component name, List<String> values) {
    return Component.translatable(
            "schematicvault.chip.group", name, String.join(VALUE_SEPARATOR, values))
        .getString();
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
    for (Chip chip : chips) {
      boolean hovered = chip.contains(mouseX, mouseY);
      int right = chip.x + chip.width;
      int bottom = chip.y + CHIP_HEIGHT;
      graphics.fill(chip.x, chip.y, right, bottom, hovered ? CHIP_HOVER_COLOR : CHIP_COLOR);
      graphics.outline(chip.x, chip.y, chip.width, CHIP_HEIGHT, BORDER_COLOR);
      int textY = chip.y + (CHIP_HEIGHT - font.lineHeight) / 2 + 1;
      graphics.text(font, chip.label, chip.x + PADDING, textY, TEXT_COLOR);
      graphics.text(
          font,
          REMOVE,
          right - PADDING - removeWidth,
          textY,
          hovered ? REMOVE_HOVER_COLOR : REMOVE_COLOR);
      if (hovered) {
        graphics.requestCursor(CursorTypes.POINTING_HAND);
      }
    }
  }

  /** Only over a chip: the gaps between chips let clicks through. */
  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    return chipAt(mouseX, mouseY) != null;
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    Chip chip = chipAt(event.x(), event.y());
    if (chip != null) {
      onChange.accept(chip.whenRemoved);
    }
  }

  /** Clicking a chip changes the filter; keyboard focus stays where it was (the search box). */
  @Override
  public boolean shouldTakeFocusAfterInteraction() {
    return false;
  }

  private @Nullable Chip chipAt(double mouseX, double mouseY) {
    for (Chip chip : chips) {
      if (chip.contains(mouseX, mouseY)) {
        return chip;
      }
    }
    return null;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, narration);
  }

  /**
   * One chip.
   *
   * @param whenRemoved the filter a click on it gives
   */
  private record Chip(String label, int x, int y, int width, CatalogFilter whenRemoved) {
    boolean contains(double mouseX, double mouseY) {
      return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + CHIP_HEIGHT;
    }
  }
}
