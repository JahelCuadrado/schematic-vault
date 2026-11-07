package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.core.catalog.CatalogIndex;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The designs that match, one row each: thumbnail, shown title (the locale's) and authors. Rows are
 * created once per design while the vault is open and reused by every search, so typing allocates
 * only the list of matches. Every row has the same height, so what vanilla computes by walking all
 * entries (the next row's top, the content height, the rows to draw) is arithmetic here: a frame
 * draws only the rows on screen, whatever the catalog size.
 */
final class SchematicList extends ObjectSelectionList<SchematicList.Row> {
  static final int ROW_HEIGHT = 24;

  static final int THUMBNAIL_SIZE = ROW_HEIGHT - 4;
  static final int TEXT_GAP = 4;
  private static final int SECOND_LINE = 10;
  private static final int TITLE_COLOR = 0xFFFFFFFF;
  private static final int AUTHOR_COLOR = 0xFFA0A0A0;
  private static final int SCROLLBAR_GAP = 2;
  private static final String ELLIPSIS = "...";
  private static final String AUTHOR_SEPARATOR = ", ";

  /** Vanilla's gap above the first row ({@code AbstractSelectionList#getFirstEntryY}). */
  private static final int TOP_PADDING = 2;

  /** Vanilla adds this below the last row ({@code AbstractSelectionList#contentHeight}). */
  private static final int CONTENT_PADDING = 4;

  private final Font font;
  private final ThumbnailCache thumbnails;
  private final Consumer<SchematicSummary> onSelect;
  private final Component noAuthor;
  private final CatalogIndex index;
  private final Row[] rows;

  /** The rows shown, in order (same as the entries, without vanilla's per-call list wrapper). */
  private Row[] shown = new Row[0];

  SchematicList(
      Minecraft minecraft,
      int width,
      int height,
      int y,
      Font font,
      ThumbnailCache thumbnails,
      CatalogIndex index,
      Component noAuthor,
      Consumer<SchematicSummary> onSelect) {
    super(minecraft, width, height, y, ROW_HEIGHT);
    this.font = font;
    this.thumbnails = thumbnails;
    this.index = index;
    this.noAuthor = noAuthor;
    this.onSelect = onSelect;
    this.rows = new Row[index.size()];
  }

  /**
   * Shows the designs at {@code matches} (catalog indices), keeping {@code selectedId} selected.
   */
  void show(int[] matches, @Nullable String selectedId) {
    List<Row> entries = new ArrayList<>(matches.length);
    Row selectedRow = null;
    for (int match : matches) {
      Row row = row(match);
      entries.add(row);
      if (row.schematic.id().equals(selectedId)) {
        selectedRow = row;
      }
    }
    shown = entries.toArray(Row[]::new);
    replaceEntries(entries);
    setScrollAmount(scrollAmount());
    if (selectedRow != null) {
      super.setSelected(selectedRow);
    }
  }

  private Row row(int catalogIndex) {
    Row row = rows[catalogIndex];
    if (row == null) {
      row = new Row(index.get(catalogIndex), index.title(catalogIndex));
      rows[catalogIndex] = row;
    }
    return row;
  }

  /** Marks the design at {@code catalogIndex}, which must be shown, without reporting a pick. */
  void highlight(int catalogIndex) {
    super.setSelected(row(catalogIndex));
  }

  /** Reports every pick, even of the selected row (a narrow window shows its details again). */
  @Override
  public void setSelected(@Nullable Row row) {
    super.setSelected(row);
    if (row != null) {
      onSelect.accept(row.schematic);
    }
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

  /** One addition, not a walk over every row (vanilla's would make a search quadratic). */
  @Override
  public int getNextY() {
    return getY() + TOP_PADDING - (int) scrollAmount() + children().size() * ROW_HEIGHT;
  }

  @Override
  protected int contentHeight() {
    return shown.length * ROW_HEIGHT + CONTENT_PADDING;
  }

  /** Only the rows inside the list's area. */
  @Override
  protected void extractListItems(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    int offset = (int) scrollAmount();
    int first = Math.max(0, (offset - TOP_PADDING) / ROW_HEIGHT);
    int last = Math.min(shown.length - 1, (offset + height) / ROW_HEIGHT);
    for (int position = first; position <= last; position++) {
      extractItem(graphics, mouseX, mouseY, partialTick, shown[position]);
    }
  }

  /** A design's row; its texts are fitted to the row width once, not every frame. */
  final class Row extends ObjectSelectionList.Entry<Row> {
    final SchematicSummary schematic;
    private final String title;
    private final String authors;
    private String fittedTitle = "";
    private String fittedAuthors = "";
    private int fittedWidth = -1;

    /**
     * The thumbnail's address, built on the row's first draw: the catalog stores it compactly and
     * {@link SchematicSummary#thumbnailPngUrl} allocates a string per call, so only rows actually
     * shown hold one, and frames after the first allocate nothing.
     */
    private @Nullable String thumbnailUrl;

    private boolean thumbnailUrlBuilt;

    Row(SchematicSummary schematic, String title) {
      this.schematic = schematic;
      this.title = title;
      this.authors =
          schematic.authors().isEmpty()
              ? noAuthor.getString()
              : String.join(AUTHOR_SEPARATOR, schematic.authors());
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
      int x = getContentX();
      int y = getContentY();
      if (!thumbnailUrlBuilt) {
        thumbnailUrl = schematic.thumbnailPngUrl();
        thumbnailUrlBuilt = true;
      }
      Thumbnails.draw(graphics, thumbnails, thumbnailUrl, x, y, THUMBNAIL_SIZE);
      int textX = x + THUMBNAIL_SIZE + TEXT_GAP;
      int textWidth = getContentRight() - textX;
      if (textWidth != fittedWidth) {
        fittedWidth = textWidth;
        fittedTitle = fit(title, textWidth);
        fittedAuthors = fit(authors, textWidth);
      }
      graphics.text(font, fittedTitle, textX, y + 1, TITLE_COLOR);
      graphics.text(font, fittedAuthors, textX, y + 1 + SECOND_LINE, AUTHOR_COLOR);
    }

    /** Without vanilla's rectangle allocation: the hover test runs for every row each frame. */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
      return mouseX >= getX()
          && mouseX < getX() + getWidth()
          && mouseY >= getY()
          && mouseY < getY() + getHeight();
    }

    @Override
    public Component getNarration() {
      return Component.literal(title);
    }

    private String fit(String text, int width) {
      if (font.width(text) <= width) {
        return text;
      }
      return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ELLIPSIS))) + ELLIPSIS;
    }
  }
}
