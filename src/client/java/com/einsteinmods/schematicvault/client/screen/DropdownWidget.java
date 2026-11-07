package com.einsteinmods.schematicvault.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * A list of options that opens below a button, over the rest of the screen: single choices (the
 * chosen one highlighted) and check boxes, each indented by its depth, with an optional item icon
 * and a grey detail on the right (a count, a range, an expand arrow). Tree rows ({@link Branch})
 * get an arrow after their indent: a click on it expands or collapses the row ({@link
 * Choices#toggle}), a click on the rest of the row chooses it. It is not one of the screen's
 * widgets: the screen draws it last, on its own GUI stratum (so it covers the list, thumbnails and
 * items included), and gives it clicks, scrolls and Escape before anything else ({@link
 * FilterBar}).
 *
 * <p>Cost: built once per opening and per expand or collapse (labels fitted to its width then); a
 * frame fills its background and draws only the rows inside it, allocating nothing of the mod's own
 * (vanilla allocates one render state per item icon drawn).
 */
final class DropdownWidget {
  static final String EXPANDED_ARROW = "▾";
  static final String COLLAPSED_ARROW = "▸";

  private static final int TEXT_ROW_HEIGHT = 12;
  private static final int ICON_SIZE = 16;
  private static final int ICON_ROW_HEIGHT = ICON_SIZE + 2;
  private static final int BORDER = 1;
  private static final int PADDING = 4;
  private static final int INDENT = 8;
  private static final int BOX_SIZE = 7;
  private static final int BOX_INSET = 2;
  private static final int GAP = 4;

  /** Room around a tree arrow that still counts as a click on it. */
  private static final int ARROW_HIT_PADDING = PADDING;

  private static final int SCROLLBAR_WIDTH = 3;
  private static final int MIN_THUMB_HEIGHT = 8;

  /** Rows the wheel moves per notch. */
  private static final int SCROLL_ROWS = 2;

  private static final String ELLIPSIS = "...";

  private static final int BACKGROUND_COLOR = 0xF0101010;
  private static final int BORDER_COLOR = 0xFFA0A0A0;
  private static final int HOVER_COLOR = 0x40FFFFFF;
  private static final int SELECTED_COLOR = 0x28FFFFFF;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int SELECTED_TEXT_COLOR = 0xFFFFFF55;
  private static final int DETAIL_COLOR = 0xFF808080;
  private static final int BOX_COLOR = 0xFFC0C0C0;
  private static final int CHECK_COLOR = 0xFF55FF55;
  private static final int TRACK_COLOR = 0x40FFFFFF;
  private static final int THUMB_COLOR = 0xFFC0C0C0;

  private final Font font;
  private final Row[] rows;
  private final String[] labels;
  private final int[] detailWidths;
  private final Choices choices;
  private final int rowHeight;
  private final int arrowWidth;
  private final int x;
  private final int y;
  private final int width;
  private final int height;
  private final int maxScroll;
  private int scroll;

  /**
   * @param top where the dropdown starts (below its button)
   * @param left preferred left edge (its button's); moved left if it would leave the screen
   * @param minWidth at least this wide (its button's width)
   */
  DropdownWidget(
      Font font,
      List<Row> rows,
      Choices choices,
      int left,
      int top,
      int minWidth,
      int screenWidth,
      int screenHeight,
      int margin) {
    this(font, rows, rows, choices, left, top, minWidth, screenWidth, screenHeight, margin);
  }

  /**
   * @param measured the rows its width is fitted to: every row a folded tree can show, so that
   *     expanding or collapsing keeps the same width
   */
  DropdownWidget(
      Font font,
      List<Row> rows,
      List<Row> measured,
      Choices choices,
      int left,
      int top,
      int minWidth,
      int screenWidth,
      int screenHeight,
      int margin) {
    this.font = font;
    this.rows = rows.toArray(Row[]::new);
    this.choices = choices;
    arrowWidth = Math.max(font.width(EXPANDED_ARROW), font.width(COLLAPSED_ARROW));
    boolean icons = false;
    for (Row row : this.rows) {
      icons |= !row.icon().isEmpty();
    }
    rowHeight = icons ? ICON_ROW_HEIGHT : TEXT_ROW_HEIGHT;
    detailWidths = new int[this.rows.length];
    for (int index = 0; index < this.rows.length; index++) {
      detailWidths[index] = detailWidth(this.rows[index]);
    }
    int contentWidth = 0;
    for (Row row : measured) {
      contentWidth =
          Math.max(contentWidth, prefixWidth(row) + font.width(row.label()) + detailWidth(row));
    }
    int frame = (BORDER + PADDING) * 2 + SCROLLBAR_WIDTH;
    int maxWidth = Math.max(minWidth, screenWidth - margin * 2);
    width = Math.clamp(contentWidth + frame, minWidth, maxWidth);
    x = Math.clamp(left, margin, Math.max(margin, screenWidth - margin - width));
    y = top;
    int contentHeight = this.rows.length * rowHeight;
    int available = Math.max(rowHeight, screenHeight - margin - top - BORDER * 2);
    height = Math.min(contentHeight, available) + BORDER * 2;
    maxScroll = Math.max(0, contentHeight - (height - BORDER * 2));
    labels = new String[this.rows.length];
    int textRight = width - frame;
    for (int index = 0; index < this.rows.length; index++) {
      labels[index] =
          fit(
              this.rows[index].label(),
              textRight - prefixWidth(this.rows[index]) - detailWidths[index]);
    }
  }

  /** Scrolls so that {@code row} shows in the middle (the chosen category when it opens). */
  void scrollTo(int row) {
    int inner = height - BORDER * 2;
    scroll = Math.clamp(row * rowHeight - (inner - rowHeight) / 2L, 0, maxScroll);
  }

  int scroll() {
    return scroll;
  }

  void setScroll(int value) {
    scroll = Math.clamp(value, 0, maxScroll);
  }

  boolean isMouseOver(double mouseX, double mouseY) {
    return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
  }

  /**
   * @return whether the dropdown should close (a single choice was made, not an arrow clicked)
   */
  boolean click(double mouseX, double mouseY) {
    int row = rowAt(mouseX, mouseY);
    if (row < 0) {
      return false;
    }
    if (isOnArrow(row, mouseX)) {
      choices.toggle(row);
      return false;
    }
    return choices.choose(row);
  }

  /** Whether {@code mouseX} is on the arrow of {@code row} (its glyph and padding, full height). */
  private boolean isOnArrow(int row, double mouseX) {
    if (!rows[row].branch().hasArrow()) {
      return false;
    }
    int arrowLeft = x + BORDER + PADDING + rows[row].depth() * INDENT;
    return mouseX >= arrowLeft - ARROW_HIT_PADDING
        && mouseX < arrowLeft + arrowWidth + ARROW_HIT_PADDING;
  }

  /** Whether a click there picks an option (for the click sound). */
  boolean isOptionAt(double mouseX, double mouseY) {
    return rowAt(mouseX, mouseY) >= 0;
  }

  void scrolled(double scrollY) {
    setScroll(scroll - (int) Math.signum(scrollY) * rowHeight * SCROLL_ROWS);
  }

  private int rowAt(double mouseX, double mouseY) {
    if (!isMouseOver(mouseX, mouseY) || mouseY < y + BORDER || mouseY >= y + height - BORDER) {
      return -1;
    }
    int row = ((int) mouseY - y - BORDER + scroll) / rowHeight;
    return row < rows.length ? row : -1;
  }

  void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    graphics.fill(x, y, x + width, y + height, BACKGROUND_COLOR);
    graphics.outline(x, y, width, height, BORDER_COLOR);
    int innerTop = y + BORDER;
    int innerBottom = y + height - BORDER;
    int rowRight = x + width - BORDER - SCROLLBAR_WIDTH;
    graphics.enableScissor(x + BORDER, innerTop, x + width - BORDER, innerBottom);
    int first = scroll / rowHeight;
    int last = Math.min(rows.length - 1, (scroll + innerBottom - innerTop - 1) / rowHeight);
    int hovered = rowAt(mouseX, mouseY);
    for (int index = first; index <= last; index++) {
      extractRow(
          graphics,
          index,
          innerTop + index * rowHeight - scroll,
          rowRight,
          index == hovered,
          mouseX);
    }
    graphics.disableScissor();
    if (maxScroll > 0) {
      int trackLeft = x + width - BORDER - SCROLLBAR_WIDTH;
      int inner = innerBottom - innerTop;
      int contentHeight = rows.length * rowHeight;
      int thumb = Math.max(MIN_THUMB_HEIGHT, inner * inner / contentHeight);
      int thumbTop = innerTop + (int) ((long) scroll * (inner - thumb) / maxScroll);
      graphics.fill(trackLeft, innerTop, trackLeft + SCROLLBAR_WIDTH, innerBottom, TRACK_COLOR);
      graphics.fill(
          trackLeft, thumbTop, trackLeft + SCROLLBAR_WIDTH, thumbTop + thumb, THUMB_COLOR);
    }
  }

  private void extractRow(
      GuiGraphicsExtractor graphics, int index, int top, int right, boolean hovered, int mouseX) {
    Row row = rows[index];
    boolean checked = choices.isChecked(index);
    if (hovered) {
      graphics.fill(x + BORDER, top, right, top + rowHeight, HOVER_COLOR);
      graphics.requestCursor(CursorTypes.POINTING_HAND);
    } else if (checked && row.kind() == Kind.CHOICE) {
      graphics.fill(x + BORDER, top, right, top + rowHeight, SELECTED_COLOR);
    }
    int left = x + BORDER + PADDING + row.depth() * INDENT;
    int textY = top + (rowHeight - font.lineHeight) / 2 + 1;
    if (row.branch() != Branch.NONE) {
      if (row.branch().hasArrow()) {
        String arrow = row.branch() == Branch.EXPANDED ? EXPANDED_ARROW : COLLAPSED_ARROW;
        int arrowColor = hovered && isOnArrow(index, mouseX) ? TEXT_COLOR : DETAIL_COLOR;
        graphics.text(font, arrow, left, textY, arrowColor);
      }
      left += arrowWidth + GAP;
    }
    if (row.kind() == Kind.CHECK) {
      int boxTop = top + (rowHeight - BOX_SIZE) / 2;
      graphics.outline(left, boxTop, BOX_SIZE, BOX_SIZE, BOX_COLOR);
      if (checked) {
        graphics.fill(
            left + BOX_INSET,
            boxTop + BOX_INSET,
            left + BOX_SIZE - BOX_INSET,
            boxTop + BOX_SIZE - BOX_INSET,
            CHECK_COLOR);
      }
      left += BOX_SIZE + GAP;
    }
    if (!row.icon().isEmpty()) {
      graphics.item(row.icon(), left, top + (rowHeight - ICON_SIZE) / 2);
      left += ICON_SIZE + GAP;
    }
    int color = checked && row.kind() == Kind.CHOICE ? SELECTED_TEXT_COLOR : TEXT_COLOR;
    graphics.text(font, labels[index], left, textY, color);
    if (detailWidths[index] > 0) {
      graphics.text(
          font, row.detail(), right - PADDING - detailWidths[index] + GAP, textY, DETAIL_COLOR);
    }
  }

  private int prefixWidth(Row row) {
    int width = row.depth() * INDENT;
    if (row.branch() != Branch.NONE) {
      width += arrowWidth + GAP;
    }
    if (row.kind() == Kind.CHECK) {
      width += BOX_SIZE + GAP;
    }
    if (!row.icon().isEmpty()) {
      width += ICON_SIZE + GAP;
    }
    return width;
  }

  private int detailWidth(Row row) {
    return row.detail().isEmpty() ? 0 : font.width(row.detail()) + GAP;
  }

  private String fit(String text, int maxWidth) {
    if (font.width(text) <= maxWidth) {
      return text;
    }
    return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS;
  }

  enum Kind {
    /** One of several options, the chosen one highlighted. */
    CHOICE,
    /** An option with a check box, on or off on its own. */
    CHECK
  }

  /** A row's place in a foldable tree: whether it has an arrow, and which way it points. */
  enum Branch {
    /** Not a tree row: no arrow and no room for one. */
    NONE,
    /** A tree row without children: room for an arrow, left empty so names line up. */
    LEAF,
    /** Children hidden: ▸. */
    COLLAPSED,
    /** Children shown below it, indented: ▾. */
    EXPANDED;

    boolean hasArrow() {
      return this == COLLAPSED || this == EXPANDED;
    }
  }

  /**
   * One line.
   *
   * @param detail grey text on the right; empty for none
   * @param icon {@link ItemStack#EMPTY} for none
   * @param depth indentation level (category tree, a filter section's options)
   */
  record Row(Kind kind, String label, String detail, ItemStack icon, int depth, Branch branch) {
    static Row choice(String label, String detail, ItemStack icon, int depth) {
      return new Row(Kind.CHOICE, label, detail, icon, depth, Branch.NONE);
    }

    static Row check(String label, String detail, int depth) {
      return new Row(Kind.CHECK, label, detail, ItemStack.EMPTY, depth, Branch.NONE);
    }

    /** A choice in a foldable tree. */
    static Row branch(String label, String detail, ItemStack icon, int depth, Branch branch) {
      return new Row(Kind.CHOICE, label, detail, icon, depth, branch);
    }

    Row withBranch(Branch value) {
      return new Row(kind, label, detail, icon, depth, value);
    }
  }

  /** What the rows mean: asked for each drawn row every frame, so it must not allocate. */
  interface Choices {
    boolean isChecked(int row);

    /**
     * @return whether the dropdown should close
     */
    boolean choose(int row);

    /** Expands or collapses a tree row (its arrow was clicked); the dropdown stays open. */
    default void toggle(int row) {}
  }
}
