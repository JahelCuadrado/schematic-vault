package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.preview.BlockList;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The block editor's side panel in {@link PreviewScreen}: the design's blocks by type (icon, name,
 * count; the replacement with its icon, its layers and an undo button once changed: a block changed
 * in some layers has one row for what it keeps and one per rule; a warning when the change drops
 * orientation or contents; for a block of a mod the player lacks, its mod and the vanilla block
 * drawn for it), the rules with no block in the layers shown under "Other changes" (undo works from
 * there), a total line, "Reset all" and "Download edited". A row opens the block picker. Built
 * again only when the list changes (a replacement, a resize), never per frame; texts are fitted
 * once per width. When the layers shown rest elsewhere {@link #show} swaps only the rows and the
 * total, so the slider keeps its focus.
 *
 * <p>Cost per frame: the rows on screen, one item icon each (two when replaced), drawn as vanilla
 * draws inventory icons.
 */
final class BlockListPanel {
  /** Narrowest panel shown: below it the full screen keeps the preview alone. */
  static final int MIN_WIDTH = 150;

  private static final int ROW_HEIGHT = 24;
  private static final int ICON_SIZE = 16;
  private static final int GAP = 4;
  private static final int UNDO_SIZE = 16;
  private static final int SECOND_LINE = 11;
  private static final int SCROLLBAR_GAP = 2;
  private static final int NAME_COLOR = 0xFFFFFFFF;
  private static final int COUNT_COLOR = 0xFFA0A0A0;
  private static final int REPLACED_COLOR = 0xFF80FF80;
  private static final int WARNING_COLOR = 0xFFFFD040;
  private static final int SHOWN_AS_COLOR = 0xFF808080;
  private static final int HEADING_COLOR = 0xFFFFD040;
  private static final String COUNT_FORMAT = "%,d";
  private static final String SCOPE_LAYERS = "schematicvault.editor.scope";
  private static final String SCOPE_ONE_LAYER = "schematicvault.editor.scope_one";
  private static final String ELLIPSIS = "...";
  private static final String ARROW = "→ ";
  private static final String WARNING = "⚠ ";

  /** "↺": undo one row. */
  private static final Component UNDO_SYMBOL = Component.literal("↺");

  private static final Component UNDO = Component.translatable("schematicvault.editor.undo");
  private static final Component RESET_ALL =
      Component.translatable("schematicvault.editor.reset_all");
  private static final Component DOWNLOAD =
      Component.translatable("schematicvault.editor.download");
  private static final Component REPLACE_HINT =
      Component.translatable("schematicvault.editor.replace_hint").withStyle(ChatFormatting.GRAY);
  private static final Component PROPERTIES_LOST =
      Component.translatable("schematicvault.editor.properties_lost")
          .withStyle(ChatFormatting.YELLOW);
  private static final Component DATA_LOST =
      Component.translatable("schematicvault.editor.data_lost").withStyle(ChatFormatting.YELLOW);
  private static final Component AIR = Component.translatable("schematicvault.picker.air");
  private static final Component OTHER_CHANGES =
      Component.translatable("schematicvault.editor.other_changes");

  /** What the panel's controls do; {@link PreviewScreen} implements them. */
  interface Actions {
    /** Opens the block picker for a row. */
    void pick(BlockList.Row row);

    void restore(BlockRule rule);

    void restoreAll();

    void downloadEdited();
  }

  private final Font font;
  private final Actions actions;
  private final StringWidget header;
  private final Rows rows;
  private final List<AbstractWidget> widgets = new ArrayList<>();
  private final int statusY;
  private BlockList shown;

  /**
   * The panel in {@code area}.
   *
   * @param scroll the list's scroll to restore (it is built again after each change)
   * @param saving whether an edited download is being written (its button waits)
   */
  BlockListPanel(
      Minecraft minecraft,
      Font font,
      ScreenRectangle area,
      BlockList list,
      double scroll,
      boolean saving,
      Actions actions) {
    this.font = font;
    this.actions = actions;
    this.shown = list;
    int x = area.left();
    int width = area.width();
    header = new StringWidget(width, font.lineHeight, total(list), font);
    header.setPosition(x, area.top());
    widgets.add(header);

    int buttonsTop = area.bottom() - Button.DEFAULT_HEIGHT;
    statusY = buttonsTop - GAP - font.lineHeight;
    int listTop = area.top() + font.lineHeight + GAP;
    int listHeight = Math.max(ROW_HEIGHT, statusY - GAP - listTop);
    rows = new Rows(minecraft, width, listHeight, listTop, list);
    // Not setX: entries move with the list only through updateSizeAndPosition.
    rows.updateSizeAndPosition(width, listHeight, x, listTop);
    rows.setScrollAmount(scroll);
    widgets.add(rows);

    int half = (width - GAP) / 2;
    Button reset =
        Button.builder(RESET_ALL, button -> actions.restoreAll())
            .bounds(x, buttonsTop, half, Button.DEFAULT_HEIGHT)
            .build();
    reset.active = list.edited();
    widgets.add(reset);
    Button download =
        Button.builder(DOWNLOAD, button -> actions.downloadEdited())
            .bounds(x + half + GAP, buttonsTop, width - half - GAP, Button.DEFAULT_HEIGHT)
            .build();
    download.active = list.edited() && !saving;
    widgets.add(download);
  }

  /**
   * Shows {@code list}, the same blocks' list for other layers; nothing when it is the shown one.
   */
  void show(BlockList list) {
    if (list == shown) {
      return;
    }
    shown = list;
    header.setMessage(total(list));
    rows.show(list);
  }

  private static Component total(BlockList list) {
    return Component.translatable(
        "schematicvault.editor.blocks", count(list.total()), list.types());
  }

  void addTo(Consumer<AbstractWidget> screen) {
    widgets.forEach(screen);
  }

  double scroll() {
    return rows.scrollAmount();
  }

  /** Where the screen draws the panel's status line (saving, saved, updating). */
  int statusY() {
    return statusY;
  }

  /**
   * {@code text} with the layers of a change, counted from 1 as the slider counts them; as it is
   * for the whole design.
   */
  static String scoped(String text, LayerRange layers) {
    if (layers.equals(LayerRange.ALL)) {
      return text;
    }
    return (layers.isSingleLayer()
            ? Component.translatable(SCOPE_ONE_LAYER, text, layers.top() + 1)
            : Component.translatable(SCOPE_LAYERS, text, layers.bottom() + 1, layers.top() + 1))
        .getString();
  }

  private static String count(long value) {
    return String.format(Locale.ROOT, COUNT_FORMAT, value);
  }

  private static ItemStack icon(@Nullable Block block) {
    if (block == null) {
      return ItemStack.EMPTY;
    }
    return block.defaultBlockState().isAir()
        ? new ItemStack(Items.BARRIER)
        : new ItemStack(block.asItem());
  }

  /**
   * The name shown for a block: the game's, "Air (remove)" for air; when the game does not know it,
   * its readable name and mod ("Redstone Sharestone (Waystones)"), or "Old block: id".
   */
  static String name(@Nullable Block block, String blockId) {
    if (block == null) {
      return StandInBlocks.isOldBlock(blockId)
          ? Component.translatable("schematicvault.editor.old_block", blockId).getString()
          : Component.translatable(
                  "schematicvault.editor.modded",
                  StandInBlocks.readable(StandInBlocks.path(blockId)),
                  StandInBlocks.readable(StandInBlocks.namespace(blockId)))
              .getString();
    }
    return block.defaultBlockState().isAir() ? AIR.getString() : block.getName().getString();
  }

  private final class Rows extends ContainerObjectSelectionList<Line> {
    Rows(Minecraft minecraft, int width, int height, int y, BlockList list) {
      super(minecraft, width, height, y, ROW_HEIGHT);
      entries(list).forEach(this::addEntry);
    }

    /** Replaces the rows, keeping the scroll where the new rows allow it. */
    void show(BlockList list) {
      replaceEntries(entries(list));
      refreshScrollAmount();
    }

    private List<Line> entries(BlockList list) {
      List<Line> entries = new ArrayList<>(list.rows().size() + list.otherRules().size() + 1);
      list.rows().forEach(row -> entries.add(new Row(row)));
      if (!list.otherRules().isEmpty()) {
        entries.add(new Heading());
        list.otherRules().forEach(row -> entries.add(new Row(row)));
      }
      return entries;
    }

    @Override
    public int getRowWidth() {
      return width - scrollbarWidth() - SCROLLBAR_GAP * 2;
    }

    @Override
    public int getRowLeft() {
      return getX() + SCROLLBAR_GAP;
    }

    @Override
    protected int scrollBarX() {
      return getRight() - scrollbarWidth();
    }
  }

  /** A line of the list. */
  private abstract static class Line extends ContainerObjectSelectionList.Entry<Line> {}

  /** "Other changes", above the rules that decide no block of the layers shown. */
  private final class Heading extends Line {
    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      int y = getContentY() + (getContentHeight() - font.lineHeight) / 2 + 1;
      graphics.text(font, OTHER_CHANGES, getContentX(), y, HEADING_COLOR);
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return List.of();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return List.of();
    }
  }

  /** One block type or rule; its texts are fitted to the row once per width. */
  private final class Row extends Line {
    private final BlockList.Row row;
    private final ItemStack icon;
    private final ItemStack replacementIcon;
    private final String name;
    private final String count;
    private final String replacement;

    /** "Shown as Glass" for a block the game does not know and no replacement changed. */
    private final String shownAs;

    private final boolean warning;
    private final List<FormattedCharSequence> tooltip;
    private final @Nullable Button undo;
    private final List<Button> buttons;
    private String fittedName = "";
    private String fittedReplacement = "";
    private String fittedShownAs = "";
    private int fittedWidth = -1;

    Row(BlockList.Row row) {
      this.row = row;
      Block shownBlock = row.shownAs();
      this.icon = icon(shownBlock != null ? shownBlock : row.block());
      this.name = name(row.block(), row.blockId());
      BlockRule rule = row.rule();
      this.count = rule != null && row.count() == 0 ? "" : count(row.count());
      Block chosen = row.replacement();
      this.replacementIcon = icon(chosen);
      this.warning = row.lostProperties() || row.dropsData();
      this.replacement =
          chosen == null || rule == null
              ? ""
              : scoped((warning ? WARNING : ARROW) + name(chosen, row.blockId()), rule.layers());
      this.shownAs =
          chosen != null || shownBlock == null
              ? ""
              : Component.translatable("schematicvault.editor.shown_as", shownBlock.getName())
                  .getString();
      List<FormattedCharSequence> lines = new ArrayList<>();
      lines.add(Component.literal(name).getVisualOrderText());
      lines.add(
          Component.literal(row.blockId())
              .withStyle(ChatFormatting.DARK_GRAY)
              .getVisualOrderText());
      if (!replacement.isEmpty()) {
        lines.add(Component.literal(replacement).getVisualOrderText());
      }
      if (!shownAs.isEmpty()) {
        lines.add(Component.literal(shownAs).withStyle(ChatFormatting.GRAY).getVisualOrderText());
      }
      if (row.lostProperties()) {
        lines.add(PROPERTIES_LOST.getVisualOrderText());
      }
      if (row.dropsData()) {
        lines.add(DATA_LOST.getVisualOrderText());
      }
      lines.add(REPLACE_HINT.getVisualOrderText());
      this.tooltip = List.copyOf(lines);
      this.undo =
          rule == null
              ? null
              : Button.builder(UNDO_SYMBOL, button -> actions.restore(rule))
                  .size(UNDO_SIZE, UNDO_SIZE)
                  .tooltip(Tooltip.create(UNDO))
                  .createNarration(unused -> AbstractWidget.wrapDefaultNarrationMessage(UNDO))
                  .build();
      this.buttons = undo == null ? List.of() : List.of(undo);
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      int x = getContentX();
      int y = getContentY();
      int right = getContentRight();
      graphics.item(icon, x, y + (getContentHeight() - ICON_SIZE) / 2);
      int textX = x + ICON_SIZE + GAP;
      Button undoButton = undo;
      int replacementRight = right;
      if (undoButton != null) {
        undoButton.setPosition(right - UNDO_SIZE, y + (getContentHeight() - UNDO_SIZE) / 2);
        undoButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        replacementRight = right - UNDO_SIZE - GAP - ICON_SIZE - GAP;
        graphics.item(
            replacementIcon,
            right - UNDO_SIZE - GAP - ICON_SIZE,
            y + (getContentHeight() - ICON_SIZE) / 2);
      }
      int nameWidth = right - textX - font.width(count) - GAP;
      if (nameWidth != fittedWidth) {
        fittedWidth = nameWidth;
        fittedName = fit(name, nameWidth);
        fittedReplacement = fit(replacement, replacementRight - textX);
        fittedShownAs = fit(shownAs, right - textX);
      }
      graphics.text(font, fittedName, textX, y + 1, NAME_COLOR);
      graphics.text(font, count, right - font.width(count), y + 1, COUNT_COLOR);
      if (undoButton != null) {
        graphics.text(
            font,
            fittedReplacement,
            textX,
            y + 1 + SECOND_LINE,
            warning ? WARNING_COLOR : REPLACED_COLOR);
      } else if (!shownAs.isEmpty()) {
        graphics.text(font, fittedShownAs, textX, y + 1 + SECOND_LINE, SHOWN_AS_COLOR);
      }
      if (hovered && (undoButton == null || !undoButton.isMouseOver(mouseX, mouseY))) {
        graphics.setTooltipForNextFrame(tooltip, mouseX, mouseY);
      }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      Button undoButton = undo;
      if (undoButton != null && undoButton.isMouseOver(event.x(), event.y())) {
        return undoButton.mouseClicked(event, doubleClick);
      }
      actions.pick(row);
      return true;
    }

    @Override
    public List<? extends GuiEventListener> children() {
      return buttons;
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
      return buttons;
    }

    private String fit(String text, int width) {
      if (width <= 0) {
        return "";
      }
      if (font.width(text) <= width) {
        return text;
      }
      return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ELLIPSIS))) + ELLIPSIS;
    }
  }
}
