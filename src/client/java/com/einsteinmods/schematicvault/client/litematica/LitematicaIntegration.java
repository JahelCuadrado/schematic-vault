package com.einsteinmods.schematicvault.client.litematica;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.cache.RowThumbnails;
import com.einsteinmods.schematicvault.client.mixin.MalilibListAccess;
import com.einsteinmods.schematicvault.client.mixin.MalilibScreenAccess;
import com.einsteinmods.schematicvault.client.preview.ThumbnailGenerator;
import com.einsteinmods.schematicvault.client.screen.BrowserPreview;
import com.einsteinmods.schematicvault.client.screen.BrowserThumbnails;
import com.einsteinmods.schematicvault.core.files.ForeignFailure;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.preview.BrowserPanelLayout;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.GuiSchematicLoadedList;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicEntry;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge to Litematica 0.29.1 (MaLiLib 0.30.2): the only class of the mod that imports
 * Litematica or MaLiLib types, created by {@link LitematicaBridge#create} only after Fabric reports
 * Litematica loaded. Each format is read as Litematica's own "Load schematic" button reads it
 * ({@code GuiSchematicLoad.ButtonListener#actionPerformedWithButton}): a {@code .litematic} through
 * {@link LitematicaSchematic#createFromFile(Path, String)}, a Sponge {@code .schem} and a vanilla
 * {@code .nbt} through {@link WorldUtils#convertSpongeSchematicToLitematicaSchematic} and {@link
 * WorldUtils#convertStructureToLitematicaSchematic(Path, String)}, an MCEdit {@code .schematic}
 * through {@link WorldUtils#convertSchematicaSchematicToLitematicaSchematic}. The result goes to
 * {@link SchematicHolder#addSchematic} (the "Loaded schematics" list); a placement is made as that
 * button makes one when "create placement on load" is on: {@link SchematicPlacement#createFor},
 * {@link SchematicPlacementManager#addSchematicPlacement} and {@link
 * SchematicPlacementManager#setSelectedSchematicPlacement}.
 *
 * <p>A {@link LinkageError} escaping Litematica (a class or method of this version is gone: its
 * internals changed) turns the integration off for the vault session, with {@link Outcome#DISABLED}
 * so the screen tells the player and drops the buttons. A runtime exception thrown inside
 * Litematica while it reads one file fails only that call ({@link Outcome#UNREADABLE}). Either way:
 * one warning line in the log, no stack trace; the game never crashes from here ({@link
 * ForeignFailure}).
 *
 * <p>Its other lists of schematics get the thumbnails already generated: {@link #listsSchematics}
 * and {@link #drawSchematicRowThumbnail}, reached through {@link LitematicaRowImages}.
 *
 * <p>The schematic browser ({@code GuiSchematicLoad}) gets the selected file in 3D in its info
 * panel, with a button: {@link #addPanelButton} and {@link #drawPanel}, reached through {@link
 * LitematicaBrowserPanel}. Its {@code GuiContext} is a {@link GuiGraphicsExtractor} made per frame
 * over the same {@code GuiRenderState} ({@code GuiContext#fromGuiGraphics}), so a
 * picture-in-picture is submitted to it as from a vanilla widget.
 *
 * <p>That screen also gets a "Generate thumbnails" button and, through a mixin on MaLiLib's {@code
 * WidgetDirectoryEntry}, each row's thumbnail: {@link #addThumbnailButton}, {@link
 * #drawRowThumbnail} and {@link #fitRows}, reached the same way, the work itself in {@link
 * BrowserThumbnails}. {@link #keepMainMenuClear} fixes its bottom row where it does not fit.
 *
 * <p>Cost: nothing per frame or tick outside that screen; a call reads the file on the render
 * thread, as Litematica's own button does. The button in Litematica's main menu costs a few text
 * measures each time that menu builds ({@link #addVaultButton}); the browser's panel, see {@link
 * BrowserPreview}.
 */
final class LitematicaIntegration implements LitematicaBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicaIntegration.class);

  /**
   * The file each schematic this mod added to Litematica was read from, for those that do not keep
   * it: a converted MCEdit file is rebuilt in a temporary world ({@link
   * LitematicaSchematic#createFromWorld}), so its {@link LitematicaSchematic#getFile} is null and
   * {@link SchematicHolder#addSchematic} cannot tell it is already loaded. Static because
   * Litematica's loaded list outlives the vault session; weak keys (Litematica's schematics do not
   * override {@code equals}) drop the entries Litematica unloads. Render thread only.
   */
  private static final Map<LitematicaSchematic, Path> SOURCE_FILES = new WeakHashMap<>();

  /**
   * Keys of the smallest {@code .litematic} data Litematica accepts without a message (current
   * schematic and data versions, no region), see {@link #readLitematicExactly}.
   */
  private static final String VERSION_KEY = "Version";

  private static final String DATA_VERSION_KEY = "MinecraftDataVersion";

  private static final String MENU_BUTTON_KEY = "schematicvault.litematica.menu_button";
  private static final String ENLARGE_KEY = "schematicvault.litematica.enlarge";
  private static final String SHOW_ANYWAY_KEY = "schematicvault.preview.show_anyway";

  /** The label {@code GuiMainMenu#initGui} gives its area selection mode button. */
  private static final String AREA_MODE_KEY = "litematica.gui.button.area_selection_mode";

  /** {@code GuiMainMenu#initGui}: the first column's left edge, the gap to the second, a button. */
  private static final int MENU_COLUMN_X = 12;

  private static final int MENU_COLUMN_GAP = 20;
  private static final int MENU_BUTTON_HEIGHT = 20;

  /**
   * Right under "Configuration menu" (y 30), with {@code GuiMainMenu#initGui}'s 22 between rows:
   * the second column is free down to y 118. The first column's gap at y 96 parts its two groups.
   */
  private static final int MENU_FREE_ROW_Y = 52;

  /** What {@code GuiMainMenu#getButtonWidth} adds to a label with an icon and to a plain one. */
  private static final int MENU_ICON_LABEL_PADDING = 30;

  private static final int MENU_PLAIN_LABEL_PADDING = 10;

  /** The menu button's icon, inset as Litematica's 14 px ones are, and its label's start. */
  private static final int MENU_ICON_X_OFFSET = 3;

  private static final int MENU_ICON_Y_OFFSET = 2;
  private static final int MENU_LABEL_X_OFFSET = 22;
  private static final int MENU_LABEL_Y_OFFSET = 6;

  /** {@code ButtonGeneric#drawText}'s label colors. */
  private static final int MENU_LABEL_COLOR = 0xFFE0E0E0;

  private static final int MENU_LABEL_HOVERED_COLOR = 0xFFFFFFFF;

  /**
   * Where {@code GuiSchematicLoad}'s list ends: its window margin (20) less its x (12), the info
   * panel (170) and {@code WidgetFileBrowserBase#getBrowserWidthForTotalWidth}'s 6, from the right.
   */
  private static final int BROWSER_LIST_RIGHT_MARGIN = 184;

  /**
   * "Litematica menu" when its row does not hold it: between the list's bottom ({@code
   * getBrowserHeight} ends it 46 from the window's) and the bottom row (26), 2 clear of each.
   */
  private static final int MAIN_MENU_RAISE = 18;

  private static final int MAIN_MENU_MOVED_HEIGHT = 16;

  /** The padding it gives up there, to the 10 of the row's other buttons. */
  private static final int MAIN_MENU_WIDTH_TRIM = 10;

  private static final String GENERATE_THUMBNAILS_KEY = "schematicvault.thumbnails.generate";

  /**
   * The thumbnails' button: on the title row, over the list ({@code GuiSchematicLoad} starts it at
   * y 24), right-aligned as that screen's "Main menu" button. Its bottom rows have no free width.
   */
  private static final int THUMBNAIL_BUTTON_Y = 2;

  private static final int THUMBNAIL_BUTTON_RIGHT_MARGIN = 10;
  private static final int THUMBNAIL_BUTTON_PADDING = 10;
  private static final int THUMBNAIL_STATUS_GAP = 6;
  private static final int THUMBNAIL_STATUS_TEXT_OFFSET = 6;
  private static final int THUMBNAIL_STATUS_COLOR = 0xFFFFFFFF;

  /** The row height {@code WidgetListBase}'s constructor gives a list. */
  private static final int LITEMATICA_ROW_HEIGHT = 14;

  /** The image's least margin above and below, which is {@code Icons.FILE_ICON_LITEMATIC}'s. */
  private static final int ROW_THUMBNAIL_MARGIN = 1;

  /** Behind an image, as {@code Thumbnails} draws it: also hides the 12 px icon it covers. */
  private static final int ROW_THUMBNAIL_BACKGROUND = 0xFF1E1E24;

  /** {@code WidgetDirectoryEntry#render}'s outline of the selected row. */
  private static final int ROW_SELECTED_OUTLINE = 0xEEEEEEEE;

  private static final int ROW_OUTLINE_WIDTH = 1;

  /** Where a loaded schematic's or a placement's image starts: inside the selected outline. */
  private static final int SCHEMATIC_ROW_IMAGE_X = 1;

  /** The info panel's button, made again each time the browser builds its buttons. */
  private static @Nullable PanelButton panelButton;

  /** Set once a call failed inside Litematica: the buttons go away for the session. */
  private boolean disabled;

  @Override
  public boolean available() {
    return !disabled;
  }

  @Override
  public boolean supports(SchematicFormat format) {
    // Litematica reads every format the vault saves; a new format must get its reader below.
    return switch (format) {
      case LITEMATIC, SCHEM, SCHEMATIC, NBT -> true;
    };
  }

  @Override
  public Outcome load(Path file, SchematicFormat format) {
    if (disabled) {
      return Outcome.DISABLED;
    }
    try {
      return loaded(file, format) == null ? failure(file, format) : Outcome.DONE;
    } catch (RuntimeException | LinkageError failure) {
      return failed(file, failure);
    }
  }

  @Override
  public Outcome place(Path file, SchematicFormat format, String name, BlockPos origin) {
    if (disabled) {
      return Outcome.DISABLED;
    }
    try {
      LitematicaSchematic schematic = loaded(file, format);
      if (schematic == null) {
        return failure(file, format);
      }
      SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
      SchematicPlacement placement =
          SchematicPlacement.createFor(schematic, origin, name, true, true);
      manager.addSchematicPlacement(placement, true);
      manager.setSelectedSchematicPlacement(placement);
      return Outcome.DONE;
    } catch (RuntimeException | LinkageError failure) {
      return failed(file, failure);
    }
  }

  /**
   * Adds the "Community schematics" button to {@code menu}, Litematica's main menu, which has just
   * built its own buttons ({@code GuiMainMenu#initGui}): in the second column, so the first keeps
   * its gap between groups. It goes in the menu with {@link GuiBase#addButton}, as the menu's own
   * buttons do, and the vault returns to the menu when it closes.
   *
   * @param menu the {@code GuiMainMenu}, as {@code Object} so the mixin names no Litematica type
   */
  static void addVaultButton(Object menu) {
    if (!(menu instanceof GuiMainMenu mainMenu)) {
      return;
    }
    String label = StringUtils.translate(MENU_BUTTON_KEY);
    int column = menuColumnWidth(mainMenu);
    int width = Math.max(column, mainMenu.getStringWidth(label) + MENU_ICON_LABEL_PADDING);
    int x = MENU_COLUMN_X + column + MENU_COLUMN_GAP;
    mainMenu.addButton(
        new VaultMenuButton(x, width, label),
        (clicked, mouseButton) ->
            SchematicVaultClient.openVault(Minecraft.getInstance(), mainMenu));
  }

  /**
   * Moves {@code screen}'s "Litematica menu" button when it lands on another button. {@code
   * GuiSchematicLoad#createButtons} right-aligns it without measuring the row to its left, so with
   * a file selected it covers "Loaded Schematics" (Spanish, 640 wide) or the rename buttons (427
   * wide): Litematica's own layout, this mod adds nothing to that row. It goes up, to the right end
   * of the free band under the list, which stays clear of the checkbox there down to 427 in
   * Spanish. It is the last button that method adds; run this before adding any other.
   *
   * @param screen the {@code GuiSchematicLoad}, as {@code Object} so the mixin names no Litematica
   *     type
   */
  static void keepMainMenuClear(Object screen) {
    if (!(screen instanceof GuiSchematicLoad browser)
        || !(screen instanceof MalilibScreenAccess access)) {
      return;
    }
    List<?> buttons = access.schematicvault$buttons();
    int last = buttons.size() - 1;
    if (last < 0 || !(buttons.get(last) instanceof ButtonBase menu)) {
      return;
    }
    for (int index = 0; index < last; index++) {
      if (buttons.get(index) instanceof ButtonBase other && overlapInRow(menu, other)) {
        int width = menu.getWidth() - MAIN_MENU_WIDTH_TRIM;
        menu.setWidth(width);
        menu.setHeight(MAIN_MENU_MOVED_HEIGHT);
        menu.setPosition(
            browser.getScreenWidth() - BROWSER_LIST_RIGHT_MARGIN - width,
            menu.getY() - MAIN_MENU_RAISE);
        return;
      }
    }
  }

  private static boolean overlapInRow(ButtonBase first, ButtonBase second) {
    return first.getX() < second.getX() + second.getWidth()
        && second.getX() < first.getX() + first.getWidth();
  }

  /**
   * Adds the info panel's button to {@code screen}, which has just built its own ({@code
   * GuiSchematicLoad#createButtons}). It starts hidden: {@link #drawPanel} places and shows it.
   *
   * @param screen the {@code GuiSchematicLoad}, as {@code Object} so the mixin names no Litematica
   *     type
   */
  static void addPanelButton(Object screen, BrowserPreview preview) {
    if (!(screen instanceof GuiSchematicLoad browser)) {
      return;
    }
    PanelButton button =
        new PanelButton(StringUtils.translate(ENLARGE_KEY), StringUtils.translate(SHOW_ANYWAY_KEY));
    panelButton = button;
    browser.addButton(button, (clicked, mouseButton) -> preview.pressButton());
  }

  /**
   * Adds the "Generate thumbnails" button to {@code screen}, which has just built its own.
   *
   * @param screen the {@code GuiSchematicLoad}, as {@code Object} so the mixin names no Litematica
   *     type
   */
  static void addThumbnailButton(Object screen, BrowserThumbnails thumbnails) {
    if (!(screen instanceof GuiSchematicLoad browser)) {
      return;
    }
    String generate = StringUtils.translate(GENERATE_THUMBNAILS_KEY);
    String cancel = thumbnails.cancelLabel();
    int width =
        Math.max(browser.getStringWidth(generate), browser.getStringWidth(cancel))
            + THUMBNAIL_BUTTON_PADDING;
    int x = browser.getScreenWidth() - width - THUMBNAIL_BUTTON_RIGHT_MARGIN;
    browser.addButton(
        new ThumbnailButton(x, width, generate, cancel, thumbnails),
        (clicked, mouseButton) -> thumbnails.press(currentFolder(browser)));
  }

  /**
   * The folder {@code browser} shows: what {@code WidgetFileBrowserBase}'s constructor and {@code
   * #switchToDirectory} keep in its directory cache, else the default.
   */
  private static Path currentFolder(GuiSchematicLoad browser) {
    Path folder =
        DataManager.getDirectoryCache().getCurrentDirectoryForContext(browser.getBrowserContext());
    return folder != null ? folder : browser.getDefaultDirectory();
  }

  /**
   * Draws {@code row}'s thumbnail over its icon when it has one, never past its real pixels. No
   * allocation once its row is known, and {@link DirectoryEntry#getFullPath} (which makes a path)
   * runs only the first time.
   *
   * @param row a {@link WidgetDirectoryEntry} in the browser's list
   * @param selected whether Litematica outlined the row, whose left edge the image covers
   */
  static void drawRowThumbnail(
      GuiGraphicsExtractor graphics, Object row, boolean selected, RowThumbnails rows) {
    if (!(row instanceof WidgetDirectoryEntry widget)) {
      return;
    }
    DirectoryEntry entry = widget.getDirectoryEntry();
    if (entry.type() != DirectoryEntryType.FILE) {
      return;
    }
    RowThumbnails.Row known = rows.row(entry);
    if (known == null) {
      rows.add(entry, entry.getFullPath());
      return;
    }
    Identifier texture = known.texture();
    if (texture != null) {
      drawRowImage(graphics, texture, widget, widget.getX(), selected);
      rows.markImageDrawn();
    }
  }

  /**
   * Whether {@code screen} lists schematics whose thumbnails {@link LitematicaRowImages} may show.
   */
  static boolean listsSchematics(Object screen) {
    return screen instanceof GuiSchematicBrowserBase
        || screen instanceof GuiSchematicLoadedList
        || screen instanceof GuiSchematicPlacementsList;
  }

  /**
   * Draws the thumbnail of {@code row}'s file over its type icon when it has one. Its rows already
   * measure 22, enough for the image, so the list keeps its height. A row is known by its
   * schematic, which outlives the widgets the list makes again, and placements of one schematic
   * share it. No allocation once it is known.
   *
   * @param row a {@link WidgetSchematicEntry} or a {@link WidgetSchematicPlacement}
   */
  static void drawSchematicRowThumbnail(
      GuiGraphicsExtractor graphics, Object row, RowThumbnails rows) {
    LitematicaSchematic schematic = rowSchematic(row);
    if (schematic == null || !(row instanceof WidgetBase widget)) {
      return;
    }
    RowThumbnails.Row known = rows.row(schematic);
    if (known == null) {
      Path file = schematic.getFile() != null ? schematic.getFile() : SOURCE_FILES.get(schematic);
      if (file != null) {
        rows.add(schematic, file);
      }
      return;
    }
    Identifier texture = known.texture();
    if (texture != null) {
      drawRowImage(graphics, texture, widget, widget.getX() + SCHEMATIC_ROW_IMAGE_X, false);
      rows.markImageDrawn();
    }
  }

  private static @Nullable LitematicaSchematic rowSchematic(Object row) {
    if (row instanceof WidgetSchematicEntry entry) {
      return entry.getEntry();
    }
    return row instanceof WidgetSchematicPlacement placement && placement.getEntry() != null
        ? placement.getEntry().getSchematic()
        : null;
  }

  /** Draws an image centred in {@code row}'s height, never past its real pixels. */
  private static void drawRowImage(
      GuiGraphicsExtractor graphics,
      Identifier texture,
      WidgetBase row,
      int left,
      boolean outlined) {
    int height = row.getHeight();
    int size =
        Math.min(
            Math.min(height - 2 * ROW_THUMBNAIL_MARGIN, BrowserThumbnails.ROW_IMAGE_SIZE),
            ThumbnailGenerator.SIZE / Minecraft.getInstance().getWindow().getGuiScale());
    int top = row.getY() + (height - size) / 2;
    graphics.fill(left, top, left + size, top + size, ROW_THUMBNAIL_BACKGROUND);
    graphics.blit(texture, left, top, left + size, top + size, 0.0F, 1.0F, 0.0F, 1.0F);
    if (outlined) {
      graphics.fill(left, top, left + ROW_OUTLINE_WIDTH, top + size, ROW_SELECTED_OUTLINE);
    }
  }

  /**
   * Gives {@code list} the taller rows when {@code images} asks for them, else Litematica's height,
   * and lays its rows out again only on a change. Clicks, the highlight and the scroll bar follow,
   * as they read the widgets and the list's height; the scroll position counts rows, so it stays.
   * Called after the list walked its rows, because laying out replaces them. Per frame the panel
   * draws: two type checks and a few field reads, no allocation.
   *
   * @param list the {@code WidgetSchematicBrowser}
   */
  static void fitRows(Object list, RowThumbnails images) {
    if (!(list instanceof WidgetSchematicBrowser browser)
        || !(list instanceof MalilibListAccess rows)) {
      return;
    }
    int height = rows.schematicvault$rowHeight();
    boolean tall = images.fitRows(browser.getCurrentDirectory(), height != LITEMATICA_ROW_HEIGHT);
    int wanted = tall ? BrowserThumbnails.ROW_HEIGHT : LITEMATICA_ROW_HEIGHT;
    if (wanted != height) {
      rows.schematicvault$setRowHeight(wanted);
      rows.schematicvault$layOutRows();
    }
  }

  /**
   * Follows the selection and draws the slot and the button over the info panel. {@code entry} is
   * compared by identity, so a frame with the same selection allocates nothing.
   *
   * @param graphics the browser's {@code GuiContext}
   * @param entry the selected {@link DirectoryEntry}; null with none
   * @param x the panel's {@code x} after drawing
   * @param y the panel's {@code y} after drawing: where its data ends
   * @param height the panel's height
   */
  static void drawPanel(
      GuiGraphicsExtractor graphics,
      @Nullable Object entry,
      int x,
      int y,
      int height,
      BrowserPreview preview) {
    PanelButton button = panelButton;
    if (button == null || !(graphics instanceof GuiContext context)) {
      return;
    }
    if (preview.selectionChanged(entry)) {
      select(entry, preview);
    }
    BrowserPanelLayout layout = preview.layout(x, y, height);
    boolean shown = preview.showsFile();
    button.update(layout, shown, preview.offersShowAnyway(), preview.buttonActive());
    if (shown) {
      preview.extract(graphics, layout);
      button.drawOverPanel(context);
    }
  }

  /** Shows the entry when it is a file of a format the mod reads, else nothing. */
  private static void select(@Nullable Object entry, BrowserPreview preview) {
    if (entry instanceof DirectoryEntry file && file.type() == DirectoryEntryType.FILE) {
      Optional<SchematicFormat> format = SchematicFormat.fromFileName(file.name());
      if (format.isPresent()) {
        preview.show(file.getFullPath(), format.get());
        return;
      }
    }
    preview.showNothing();
  }

  /**
   * The width of the menu's columns, measured as the private {@code GuiMainMenu#getButtonWidth}
   * does: the second column starts from it, and the button lines up with that column.
   */
  private static int menuColumnWidth(GuiBase menu) {
    int width = 0;
    for (GuiMainMenu.ButtonListenerChangeMenu.ButtonType type :
        GuiMainMenu.ButtonListenerChangeMenu.ButtonType.values()) {
      width = Math.max(width, menu.getStringWidth(type.getDisplayName()) + MENU_ICON_LABEL_PADDING);
    }
    for (SelectionMode mode : SelectionMode.values()) {
      String modeLabel = StringUtils.translate(AREA_MODE_KEY, mode.getDisplayName());
      width = Math.max(width, menu.getStringWidth(modeLabel) + MENU_PLAIN_LABEL_PADDING);
    }
    return width;
  }

  /**
   * The file's schematic in Litematica's loaded list, read and added first if it is not there yet
   * ({@link SchematicHolder#getAllSchematics}, matched by {@link LitematicaSchematic#getFile} or,
   * for a converted MCEdit file, by {@link #SOURCE_FILES}); null when Litematica cannot read it.
   */
  private static @Nullable LitematicaSchematic loaded(Path file, SchematicFormat format) {
    SchematicHolder holder = SchematicHolder.getInstance();
    for (LitematicaSchematic schematic : holder.getAllSchematics()) {
      if (file.equals(schematic.getFile()) || file.equals(SOURCE_FILES.get(schematic))) {
        return schematic;
      }
    }
    LitematicaSchematic schematic = read(file, format);
    if (schematic != null) {
      holder.addSchematic(schematic, false);
      if (schematic.getFile() == null) {
        SOURCE_FILES.put(schematic, file);
      }
    }
    return schematic;
  }

  /** Reads the file as Litematica's load button does for its type; null when it cannot. */
  private static @Nullable LitematicaSchematic read(Path file, SchematicFormat format) {
    Path folder = file.getParent();
    String fileName = file.getFileName().toString();
    return switch (format) {
      case LITEMATIC ->
          keepsName(file, FileType.LITEMATICA_SCHEMATIC)
              ? LitematicaSchematic.createFromFile(folder, fileName)
              : readLitematicExactly(file);
      case SCHEM ->
          keepsName(file, FileType.SPONGE_SCHEMATIC)
              ? WorldUtils.convertSpongeSchematicToLitematicaSchematic(folder, fileName)
              : null;
      case NBT ->
          keepsName(file, FileType.VANILLA_STRUCTURE)
              ? WorldUtils.convertStructureToLitematicaSchematic(folder, fileName)
              : null;
      case SCHEMATIC ->
          WorldUtils.convertSchematicaSchematicToLitematicaSchematic(
              folder, fileName, false, InfoUtils.INFO_MESSAGE_CONSUMER);
    };
  }

  /**
   * A {@code .litematic} whose name {@link LitematicaSchematic#fileFromDirAndName} would rewrite,
   * read as {@code LitematicaSchematic#createFromFile(Path, String, FileType)} reads it but from
   * its exact path. That method's constructor is private; the public {@link
   * LitematicaSchematic#LitematicaSchematic(Path, CompoundData, FileType) constructor} reads its
   * data before it creates the {@code SchematicConverter} that a file saved by an older game needs
   * ({@code postProcessContainerIfNeeded}: a {@code NullPointerException}), so it gets the smallest
   * valid data and {@link LitematicaSchematic#readFromFile()} then reads the file with the
   * converter in place, exactly as {@code createFromFile} does. Null when Litematica cannot read it
   * (it shows why).
   */
  private static @Nullable LitematicaSchematic readLitematicExactly(Path file) {
    CompoundData empty =
        new CompoundData()
            .putInt(VERSION_KEY, LitematicaSchematic.SCHEMATIC_VERSION)
            .putInt(DATA_VERSION_KEY, LitematicaSchematic.MINECRAFT_DATA_VERSION);
    LitematicaSchematic schematic =
        new LitematicaSchematic(file, empty, FileType.LITEMATICA_SCHEMATIC);
    return schematic.readFromFile() ? schematic : null;
  }

  /**
   * Whether Litematica opens {@code file} itself: {@link LitematicaSchematic#fileFromDirAndName},
   * which {@code createFromFile} uses for every type, strips "COM" and "PRN" from any name holding
   * them ("COMPACT farm" becomes "PACT farm") and would read another file. MCEdit files are read
   * from their exact path ({@code SchematicaSchematic#createFromFile(Path)}).
   */
  private static boolean keepsName(Path file, FileType type) {
    Path parent = file.getParent();
    return parent != null
        && file.equals(
            LitematicaSchematic.fileFromDirAndName(parent, file.getFileName().toString(), type));
  }

  /** Why {@code file} gave no schematic: its rewritten name, or Litematica could not read it. */
  private static Outcome failure(Path file, SchematicFormat format) {
    return switch (format) {
      case SCHEM ->
          keepsName(file, FileType.SPONGE_SCHEMATIC) ? Outcome.UNREADABLE : Outcome.NAME_REJECTED;
      case NBT ->
          keepsName(file, FileType.VANILLA_STRUCTURE) ? Outcome.UNREADABLE : Outcome.NAME_REJECTED;
      case LITEMATIC, SCHEMATIC -> Outcome.UNREADABLE;
    };
  }

  /** Reports a failure escaping Litematica for {@code file}: that file, or the whole session. */
  private Outcome failed(Path file, Throwable failure) {
    return switch (ForeignFailure.of(failure)) {
      case ONE_FILE -> {
        LOGGER.warn("Litematica could not open {}: {}", file.getFileName(), failure.toString());
        yield Outcome.UNREADABLE;
      }
      case INCOMPATIBLE -> {
        disabled = true;
        LitematicaMenuButton.disable();
        LitematicaBrowserPanel.disable();
        LOGGER.warn("Litematica integration turned off for this session: {}", failure.toString());
        yield Outcome.DISABLED;
      }
    };
  }

  /**
   * The vault's button in Litematica's menu, laid out like the menu's own: an icon, then the label
   * left-aligned. Those use a texture of Litematica's ({@code ButtonIcons}), which has nothing for
   * the vault, so {@link ButtonGeneric} gets no label and this draws both.
   */
  private static final class VaultMenuButton extends ButtonGeneric {
    /** Created when the menu first draws this button, never per frame. */
    private static final ItemStack ICON = new ItemStack(Items.BOOK);

    private final String label;

    VaultMenuButton(int x, int width, String label) {
      super(x, MENU_FREE_ROW_Y, width, MENU_BUTTON_HEIGHT, "");
      this.label = label;
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
      super.render(context, mouseX, mouseY, selected);
      context.renderItem(ICON, x + MENU_ICON_X_OFFSET, y + MENU_ICON_Y_OFFSET);
      drawStringWithShadow(
          context,
          x + MENU_LABEL_X_OFFSET,
          y + MENU_LABEL_Y_OFFSET,
          hovered ? MENU_LABEL_HOVERED_COLOR : MENU_LABEL_COLOR,
          label);
    }
  }

  /**
   * The info panel's button. Its screen draws buttons ({@code GuiBase#drawButtons}) before the
   * list, so the panel's dark box would cover it: that call only notes the pointer, and {@link
   * #drawOverPanel} draws it once the panel is done. It stays a button of the screen for its
   * clicks.
   */
  private static final class PanelButton extends ButtonGeneric {
    private final String enlargeLabel;
    private final String showAnywayLabel;
    private int mouseX;
    private int mouseY;
    private boolean showsAnyway;

    PanelButton(String enlargeLabel, String showAnywayLabel) {
      super(0, 0, 0, BrowserPanelLayout.BUTTON_HEIGHT, enlargeLabel);
      this.enlargeLabel = enlargeLabel;
      this.showAnywayLabel = showAnywayLabel;
      visible = false;
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
      this.mouseX = mouseX;
      this.mouseY = mouseY;
    }

    void update(BrowserPanelLayout layout, boolean shown, boolean offersAnyway, boolean active) {
      setPosition(layout.slotLeft(), layout.buttonTop());
      setWidth(layout.slotRight() - layout.slotLeft());
      visible = shown;
      enabled = active;
      if (offersAnyway != showsAnyway) {
        showsAnyway = offersAnyway;
        setDisplayString(offersAnyway ? showAnywayLabel : enlargeLabel);
      }
    }

    void drawOverPanel(GuiContext context) {
      super.render(context, mouseX, mouseY, false);
    }
  }

  /**
   * "Generate thumbnails", or "Cancel" while they are made, with what they are doing to its left.
   * Hidden until a row has been drawn through the row mixin: if that mixin did not apply nothing
   * could show them. The screen makes it again on each selection, so what it shows lives in {@link
   * BrowserThumbnails}.
   */
  private static final class ThumbnailButton extends ButtonGeneric {
    private final String generateLabel;
    private final String cancelLabel;
    private final BrowserThumbnails thumbnails;
    private boolean showsCancel;
    private String status = "";
    private int statusWidth;

    ThumbnailButton(
        int x, int width, String generateLabel, String cancelLabel, BrowserThumbnails thumbnails) {
      super(x, THUMBNAIL_BUTTON_Y, width, MENU_BUTTON_HEIGHT, generateLabel);
      this.generateLabel = generateLabel;
      this.cancelLabel = cancelLabel;
      this.thumbnails = thumbnails;
      visible = false;
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
      visible = LitematicaBrowserPanel.rowHookWorks();
      boolean running = thumbnails.running();
      if (running != showsCancel) {
        showsCancel = running;
        setDisplayString(running ? cancelLabel : generateLabel);
      }
      super.render(context, mouseX, mouseY, selected);
      // The same string until it changes: measured once.
      String shown = thumbnails.status();
      if (shown != status) {
        status = shown;
        statusWidth = getStringWidth(shown);
      }
      if (visible && !shown.isEmpty()) {
        drawString(
            context,
            getX() - statusWidth - THUMBNAIL_STATUS_GAP,
            getY() + THUMBNAIL_STATUS_TEXT_OFFSET,
            THUMBNAIL_STATUS_COLOR,
            shown);
      }
    }
  }
}
