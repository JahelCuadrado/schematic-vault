package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.VaultSession;
import com.einsteinmods.schematicvault.client.VaultSession.DetailView;
import com.einsteinmods.schematicvault.client.cache.CatalogListener;
import com.einsteinmods.schematicvault.client.cache.LoadedCatalog;
import com.einsteinmods.schematicvault.client.config.VaultSettingsScreen;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge.Outcome;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.core.catalog.CatalogFilter;
import com.einsteinmods.schematicvault.core.catalog.CatalogIndex;
import com.einsteinmods.schematicvault.core.catalog.CatalogSearch;
import com.einsteinmods.schematicvault.core.catalog.CategoryTree;
import com.einsteinmods.schematicvault.core.catalog.SchematicFile;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import com.einsteinmods.schematicvault.core.config.VaultSettings;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.mojang.blaze3d.Blaze3D;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The vault: the search and filter bar on top ({@link FilterBar}) with the active filters below it
 * ({@link FilterChips}); the matching designs on the left; the selected design's details on the
 * right (in a narrow window the details replace the list, with a Back button). The cached catalog
 * shows as soon as it is read; the refresh runs behind it with its state in the footer. The filter
 * is kept for the game session ({@link FilterMemory}). Above the list, in a category without
 * subcategories, the best design of each difficulty ({@link FeaturedRow}), when the window leaves
 * room for them.
 *
 * <p>Cost: nothing while closed (no tick or render hook). While open, a frame draws the widgets on
 * screen and the visible rows only ({@link SchematicList}), allocating nothing of the mod's own;
 * the search runs once per change of the filter on its own thread ({@link CatalogSearch}: one pass
 * over the catalog, or over the last matches while typing on, no sorting) and its matches replace
 * the shown ones a frame or so later; only a new catalog (cache read, new revision) is filtered on
 * the render thread, once. The detail panel is rebuilt only when what it shows changes. The session
 * (HTTP client, textures) is closed when the vault closes, not when its own link prompt or
 * full-screen preview ({@link PreviewScreen}) opens. The 3D preview costs nothing until a design is
 * selected; see {@link PreviewWidget}.
 *
 * <p>Litematica (optional): the detail panel's and the full screen's Litematica buttons end here.
 * The file is downloaded first when it is not saved yet, an edited design is saved first under the
 * name the player types (the last such save is reused while the edits stay the same), and "Place
 * here" closes the vault so the placement shows at once. Litematica runs on the render thread on
 * that click only; it costs nothing per frame.
 */
public final class VaultScreen extends Screen
    implements CatalogListener, DetailPanel.Actions, PreviewHost {
  private static final Component TITLE = Component.translatable("schematicvault.title");
  private static final Component NO_AUTHOR =
      Component.translatable("schematicvault.detail.unknown_author");
  private static final Component RETRY = Component.translatable("schematicvault.retry");
  private static final Component NO_RESULTS = Component.translatable("schematicvault.results.none");
  private static final Component NO_SELECTION =
      Component.translatable("schematicvault.detail.none");
  private static final Component CHECKING =
      Component.translatable("schematicvault.status.checking");

  /** "⚙", the settings button in the bar's reserved slot. */
  private static final Component SETTINGS_SYMBOL = Component.literal("\u2699");

  private static final Tooltip SETTINGS_TOOLTIP =
      Tooltip.create(Component.translatable("schematicvault.settings"));

  private static final String AUTHOR_SEPARATOR = ", ";

  private static final int MARGIN = 6;
  private static final int GAP = 4;
  private static final int BAR_HEIGHT = FilterBar.HEIGHT;
  private static final int FOOTER_HEIGHT = Button.DEFAULT_HEIGHT;
  private static final int DONE_WIDTH = 80;
  private static final int RETRY_WIDTH = 100;
  private static final int MIN_LIST_WIDTH = 160;
  private static final int MAX_LIST_WIDTH = 360;

  /** The featured row shows only when the list keeps this many rows of height under it. */
  private static final int MIN_ROWS_UNDER_FEATURED = 3;

  /** Below this width the details replace the list instead of sharing the screen with it. */
  private static final int NARROW_WIDTH = 400;

  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int STATUS_COLOR = 0xFFA0A0A0;
  private static final int ERROR_COLOR = 0xFFFF6060;

  /** Length of the date in an ISO-8601 instant ({@code 2026-10-28}). */
  private static final int ISO_DATE_LENGTH = 10;

  private final @Nullable Screen parent;
  private final VaultSession session;
  private final String minecraftLanguage;

  private @Nullable LoadedCatalog catalog;
  private CatalogStatus status = CatalogStatus.READING_CACHE;
  private @Nullable FailureKind failure;
  private int pagesDone;
  private int pageCount;

  private CatalogFilter filter = FilterMemory.get();
  private int[] matches = new int[0];

  /** Catalog indices of the featured designs among {@link #matches}. */
  private int[] featured = new int[0];

  /** Filters off the render thread; closed with the session. */
  private final CatalogSearch search;

  /**
   * A filter change asked for the list's top: kept until matches arrive, even if a new catalog's
   * matches replace the pending ones.
   */
  private boolean scrollToTopPending;

  /**
   * Designs whose record the API no longer has (HTTP 404), hidden from the list until the screen
   * closes; the next revision refresh drops them from the cached catalog.
   */
  private final Set<String> removedIds = new HashSet<>();

  private @Nullable SchematicSummary selected;
  private DetailPanel.RecordState recordState = DetailPanel.RecordState.LOADING;
  private @Nullable DetailView detail;
  private final Map<String, DownloadState> downloads = new HashMap<>();

  /** In a narrow window: the details are shown instead of the list. */
  private boolean showingDetail;

  /** Set while one of the vault's own sub-screens is open, so the session stays open. */
  private boolean openingChild;

  /** Set once the session is closed: the vault is gone, even if a download still finishes. */
  private boolean closed;

  /** The edited copies saved in this vault. */
  private final EditedCopies editedCopies;

  private final FilterBar bar;
  private final FilterChips chips;
  private @Nullable SchematicList list;
  private @Nullable FeaturedRow featuredRow;

  /** The list's scroll, kept while the widgets are rebuilt (a sub-screen, a resize). */
  private double listScroll;

  private @Nullable Layout detailPanel;

  /** The detail panel's preview, if shown: the screen forwards its right-drags. */
  private @Nullable PreviewWidget preview;

  private Component footer = CHECKING;

  /** Over the empty list while the first download runs ("Downloading the catalog… 12/64"). */
  private Component downloadingMessage = CHECKING;

  /** Over the empty list when there is no catalog to show. */
  private Component unavailableMessage = Component.empty();

  private int contentTop;
  private int contentBottom;
  private int listLeft;
  private int listWidth;
  private int detailLeft;
  private int detailWidth;

  public VaultScreen(@Nullable Screen parent, VaultSession session) {
    super(TITLE);
    this.parent = parent;
    this.session = session;
    this.minecraftLanguage = minecraft.getLanguageManager().getSelected();
    this.search = new CatalogSearch(minecraft);
    this.bar = new FilterBar(minecraft, font, filter, this::onFilterChanged);
    this.chips = new FilterChips(font, this::onFilterChanged);
    this.editedCopies =
        new EditedCopies(
            minecraft, session.previews(), session.downloader()::saveEdited, this::deliver);
    session.catalog().open(minecraftLanguage, this);
    session.previews().listen(this::onPreviewChanged);
  }

  @Override
  public void added() {
    openingChild = false;
    // The click that opened a sub-screen was released there: no drag goes on here.
    setDragging(false);
  }

  @Override
  protected void init() {
    boolean narrow = width < NARROW_WIDTH;
    int footerTop = height - MARGIN - FOOTER_HEIGHT;
    int controlsWidth = width - MARGIN * 2;
    LoadedCatalog shown = catalog;
    int barBottom = bar.build(MARGIN, MARGIN, controlsWidth, shown, width, height, MARGIN);
    bar.visitWidgets(this::addRenderableWidget);
    addRenderableWidget(
        bar.placeInGearSlot(
            Button.builder(SETTINGS_SYMBOL, button -> openSettings())
                .tooltip(SETTINGS_TOOLTIP)
                .build()));
    chips.layout(
        MARGIN,
        barBottom + GAP,
        controlsWidth,
        filter,
        shown == null ? List.of() : shown.categories());
    addRenderableWidget(chips);
    addRenderableWidget(
        Button.builder(CommonComponents.GUI_DONE, button -> onClose())
            .bounds(width - MARGIN - DONE_WIDTH, footerTop, DONE_WIDTH, FOOTER_HEIGHT)
            .build());

    contentTop = chips.getHeight() == 0 ? barBottom + GAP : chips.getBottom() + GAP;
    contentBottom = footerTop - GAP;
    if (narrow) {
      listLeft = MARGIN;
      listWidth = width - MARGIN * 2;
      detailLeft = MARGIN;
      detailWidth = listWidth;
    } else {
      listLeft = MARGIN;
      listWidth = Math.clamp((width - MARGIN * 2) * 45 / 100, MIN_LIST_WIDTH, MAX_LIST_WIDTH);
      detailLeft = listLeft + listWidth + GAP * 2;
      detailWidth = width - MARGIN - detailLeft;
    }
    boolean listShown = !narrow || !showingDetail || selected == null;
    boolean detailShown = !narrow || showingDetail && selected != null;
    SchematicList oldList = list;
    if (oldList != null) {
      listScroll = oldList.scrollAmount();
    }
    list = null;
    featuredRow = null;
    int listTop = contentTop;
    if (listShown && shown != null && featuredFits()) {
      FeaturedRow row =
          new FeaturedRow(
              listLeft,
              contentTop,
              listWidth,
              font,
              session.thumbnails(),
              shown.index(),
              this::onFeaturedSelected);
      row.show(featured, selected == null ? null : selected.id());
      featuredRow = addRenderableWidget(row);
      listTop = row.getBottom() + GAP;
    }
    if (listShown && shown != null) {
      SchematicList schematics =
          new SchematicList(
              minecraft,
              listWidth,
              Math.max(SchematicList.ROW_HEIGHT, contentBottom - listTop),
              listTop,
              font,
              session.thumbnails(),
              shown.index(),
              NO_AUTHOR,
              this::onSelected);
      schematics.setX(listLeft);
      schematics.show(matches, selected == null ? null : selected.id());
      schematics.setScrollAmount(listScroll);
      list = addRenderableWidget(schematics);
    }
    if (listShown && shown == null && status == CatalogStatus.UNAVAILABLE) {
      addRenderableWidget(
          Button.builder(RETRY, button -> retry())
              .bounds(
                  listLeft + (listWidth - RETRY_WIDTH) / 2,
                  contentTop + contentHeight() / 2 + font.lineHeight,
                  RETRY_WIDTH,
                  Button.DEFAULT_HEIGHT)
              .build());
    }
    detailPanel = null;
    if (detailShown) {
      if (narrow) {
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK, button -> showList())
                .bounds(detailLeft, contentTop, Math.min(DONE_WIDTH, detailWidth), BAR_HEIGHT)
                .build());
      }
      addDetailPanel();
    }
    EditBox search = bar.searchBox();
    if (search != null) {
      setInitialFocus(search);
    }
  }

  /** Whether there are featured designs and the row leaves a usable list under it. */
  private boolean featuredFits() {
    return featured.length > 0
        && contentHeight()
            >= FeaturedRow.height(listWidth, font)
                + GAP
                + MIN_ROWS_UNDER_FEATURED * SchematicList.ROW_HEIGHT;
  }

  private int contentHeight() {
    return Math.max(SchematicList.ROW_HEIGHT, contentBottom - contentTop);
  }

  /**
   * (Re)creates the detail panel of the selected design, if any, first pointing the preview at it
   * (a no-op when it already shows it).
   */
  private void addDetailPanel() {
    Layout old = detailPanel;
    if (old != null) {
      old.visitWidgets(this::removeWidget);
      detailPanel = null;
      preview = null;
    }
    SchematicSummary schematic = selected;
    LoadedCatalog loaded = catalog;
    if (schematic == null || loaded == null) {
      return;
    }
    DetailView view = detail;
    if (view == null) {
      session.previews().show(schematic, null, List.of(), Map.of());
    } else {
      session
          .previews()
          .show(
              schematic,
              view.detail().primaryFile().orElse(null),
              view.detail().files(),
              view.saved());
    }
    boolean narrow = width < NARROW_WIDTH;
    int top = narrow ? contentTop + BAR_HEIGHT + GAP : contentTop;
    Layout panel =
        DetailPanel.build(
            minecraft,
            font,
            detailLeft,
            top,
            detailWidth,
            Math.max(BAR_HEIGHT, contentBottom - top),
            session.thumbnails(),
            session.previews(),
            schematic,
            loaded.catalog().locale(),
            recordState,
            detail,
            this::downloadState,
            session.litematica(),
            this);
    panel.visitWidgets(this::addRenderableWidget);
    panel.visitWidgets(
        widget -> {
          if (widget instanceof PreviewWidget shown) {
            preview = shown;
          }
        });
    detailPanel = panel;
  }

  /** A preview finished loading (or failed): its notices and the 3D replace the thumbnail. */
  private void onPreviewChanged() {
    if (minecraft.gui.screen() == this) {
      addDetailPanel();
    }
  }

  @Override
  public void previewFile(SchematicFile file) {
    DetailView view = detail;
    session.previews().choose(file, view == null ? null : view.saved().get(file.id()));
    addDetailPanel();
  }

  @Override
  public void showPreviewAnyway() {
    session.previews().showAnyway();
    addDetailPanel();
  }

  @Override
  public void showFullScreen() {
    SchematicSummary schematic = selected;
    LoadedCatalog loaded = catalog;
    if (schematic == null || loaded == null) {
      return;
    }
    Component title =
        Component.literal(DetailPanel.title(schematic, loaded.catalog().locale(), detail));
    openingChild = true;
    minecraft.gui.setScreen(
        new PreviewScreen(
            this,
            title,
            PreviewWidget.thumbnail(session.thumbnails(), schematic.thumbnailPngUrl()),
            session.previews(),
            session.litematica()));
  }

  /**
   * A new filter from the bar, its dropdowns or a chip. The chips are laid out again; when they
   * take another number of rows the list moves, so the widgets are rebuilt (an open dropdown
   * stays).
   */
  private void onFilterChanged(CatalogFilter newFilter) {
    filter = newFilter;
    FilterMemory.set(newFilter);
    bar.update(newFilter);
    requestFilter();
    int oldHeight = chips.getHeight();
    LoadedCatalog shown = catalog;
    chips.layout(
        chips.getX(),
        chips.getY(),
        chips.getWidth(),
        newFilter,
        shown == null ? List.of() : shown.categories());
    if (chips.getHeight() != oldHeight) {
      rebuildWidgets();
    }
  }

  /**
   * Searches for {@link #filter} on the search thread; the shown matches stay until the new ones
   * arrive, then the list goes to its top.
   */
  private void requestFilter() {
    LoadedCatalog shown = catalog;
    scrollToTopPending = true;
    if (shown == null) {
      applyFilter(true);
      return;
    }
    search.request(shown.index(), filter, found -> showMatches(found, true));
  }

  /**
   * Searches for {@link #filter} on the render thread and shows the matches at once, dropping any
   * pending search: for a new catalog, whose widgets are rebuilt right after (the old matches
   * belong to the old index), and a design gone from the API.
   */
  private void applyFilter(boolean scrollToTop) {
    LoadedCatalog shown = catalog;
    if (shown == null) {
      search.cancel();
      showMatches(new int[0], scrollToTop);
    } else {
      showMatches(search.filterNow(shown.index(), filter), scrollToTop);
    }
  }

  /** Shows {@code found}, matches of {@link #filter} in the current catalog. */
  private void showMatches(int[] found, boolean scrollToTop) {
    LoadedCatalog shown = catalog;
    boolean toTop = scrollToTop || scrollToTopPending;
    scrollToTopPending = false;
    matches = found;
    if (shown != null && !removedIds.isEmpty()) {
      matches = withoutRemoved(matches, shown.index());
    }
    boolean hadFeatured = featured.length > 0;
    featured =
        shown != null && shown.catalog().isLeafCategory(filter.categoryPath())
            ? shown.index().featured(matches)
            : new int[0];
    SchematicList schematics = list;
    if (toTop) {
      listScroll = 0;
    }
    if (schematics != null) {
      schematics.show(matches, selected == null ? null : selected.id());
      if (toTop) {
        schematics.setScrollAmount(0);
      }
    }
    FeaturedRow row = featuredRow;
    if (row != null) {
      row.show(featured, selected == null ? null : selected.id());
    }
    updateFooter();
    if (hadFeatured != featured.length > 0 && schematics != null) {
      // The row came or went: the list moves.
      rebuildWidgets();
    }
  }

  /** A featured card was clicked: as a click on the design's row in the list. */
  private void onFeaturedSelected(int catalogIndex) {
    LoadedCatalog shown = catalog;
    SchematicList schematics = list;
    if (shown == null) {
      return;
    }
    if (schematics != null) {
      schematics.highlight(catalogIndex);
    }
    onSelected(shown.index().get(catalogIndex));
  }

  private int[] withoutRemoved(int[] found, CatalogIndex index) {
    int kept = 0;
    int[] result = new int[found.length];
    for (int match : found) {
      if (!removedIds.contains(index.get(match).id())) {
        result[kept++] = match;
      }
    }
    return kept == found.length ? found : Arrays.copyOf(result, kept);
  }

  private void onSelected(SchematicSummary schematic) {
    boolean narrow = width < NARROW_WIDTH;
    if (selected != null && selected.id().equals(schematic.id())) {
      if (narrow && !showingDetail) {
        showingDetail = true;
        rebuildWidgets();
      }
      return;
    }
    selected = schematic;
    FeaturedRow row = featuredRow;
    if (row != null) {
      row.select(schematic.id());
    }
    DetailView cached = session.cachedDetail(schematic.id());
    if (cached != null) {
      detail = cached;
      recordState = DetailPanel.RecordState.LOADED;
    } else {
      detail = null;
      recordState = DetailPanel.RecordState.LOADING;
      loadDetail(schematic.id());
    }
    if (narrow) {
      showingDetail = true;
      rebuildWidgets();
    } else {
      addDetailPanel();
    }
  }

  private void loadDetail(String id) {
    session
        .loadDetail(id)
        .whenCompleteAsync(
            (view, loadFailure) -> {
              if (view != null) {
                session.rememberDetail(view);
              }
              boolean gone = loadFailure != null && ApiException.isNotFound(loadFailure);
              if (gone && removedIds.add(id)) {
                applyFilter(false);
              }
              SchematicSummary current = selected;
              if (current == null || !current.id().equals(id)) {
                return;
              }
              detail = view;
              recordState =
                  view != null
                      ? DetailPanel.RecordState.LOADED
                      : gone ? DetailPanel.RecordState.GONE : DetailPanel.RecordState.FAILED;
              addDetailPanel();
            },
            minecraft);
  }

  private void showList() {
    showingDetail = false;
    rebuildWidgets();
  }

  private DownloadState downloadState(SchematicFile file) {
    DownloadState state = downloads.get(file.sha256());
    if (state != null) {
      return state;
    }
    DetailView view = detail;
    Path saved = view == null ? null : view.saved().get(file.id());
    return saved == null ? DownloadState.IDLE : DownloadState.done(saved);
  }

  @Override
  public void download(SchematicFile file) {
    startDownload(file);
  }

  /**
   * Downloads {@code file}, showing its progress in the panel; completes on the render thread once
   * the panel shows the result.
   */
  private CompletableFuture<Path> startDownload(SchematicFile file) {
    String key = file.sha256();
    downloads.put(key, DownloadState.IN_PROGRESS);
    if (minecraft.gui.screen() == this) {
      // From the full screen view the vault shows it on return (init).
      addDetailPanel();
    }
    return session
        .downloader()
        .download(file)
        .whenCompleteAsync(
            (saved, downloadFailure) -> {
              downloads.put(
                  key,
                  saved != null
                      ? DownloadState.done(saved)
                      : DownloadState.failed(ApiException.kindOf(downloadFailure)));
              if (minecraft.gui.screen() == this) {
                addDetailPanel();
              }
            },
            minecraft);
  }

  @Override
  public void sendToLitematica(SchematicFile file, LitematicaAction action) {
    SchematicFile shown = session.previews().file();
    boolean edited = session.previews().edited() && shown != null && shown.id().equals(file.id());
    if (!edited) {
      sendSaved(file, action);
    } else if (!sendLastEdited(action)) {
      askEditedName(this, name -> saveEdited(name, action));
    }
  }

  /**
   * Hands the selected design's {@code file} as the API gives it to Litematica, downloading it
   * first when it is not saved yet (a download in progress is left to finish).
   */
  void sendSaved(SchematicFile file, LitematicaAction action) {
    DownloadState state = downloadState(file);
    String designTitle = selectedTitle();
    Path saved = state.saved();
    switch (state.status()) {
      case IN_PROGRESS -> {
        // Its Litematica buttons are off until it finishes.
      }
      case DONE -> {
        if (saved != null) {
          deliver(action, saved, file.format(), designTitle);
        }
      }
      case IDLE, FAILED ->
          startDownload(file)
              .whenComplete(
                  (path, failure) -> {
                    if (path != null) {
                      deliver(action, path, file.format(), designTitle);
                    } else {
                      LitematicaButtons.show(
                          minecraft,
                          Component.translatable("schematicvault.litematica.download_failed"),
                          Component.translatable(
                              DetailPanel.failureKey(ApiException.kindOf(failure))));
                    }
                  });
    }
  }

  @Override
  public void closePreview() {
    minecraft.gui.setScreen(this);
  }

  @Override
  public void sendShown(LitematicaAction action) {
    SchematicFile shown = session.previews().file();
    if (shown != null) {
      sendSaved(shown, action);
    }
  }

  @Override
  public boolean sendLastEdited(LitematicaAction action) {
    return editedCopies.sendLast(action);
  }

  @Override
  public void askEditedName(Screen returnTo, Consumer<String> onSave) {
    SchematicFormat format = session.previews().editedFormat();
    if (format == null) {
      return;
    }
    openingChild = true;
    minecraft.gui.setScreen(
        new SaveEditedScreen(
            returnTo,
            SaveEditedScreen.editedName(selectedTitle()),
            SaveEditedScreen.formatLine(format),
            onSave));
  }

  @Override
  public CompletableFuture<Path> saveEdited(String name, @Nullable LitematicaAction then) {
    SchematicSummary schematic = selected;
    String author = schematic == null ? "" : String.join(AUTHOR_SEPARATOR, schematic.authors());
    return editedCopies.save(name, author, then);
  }

  /**
   * Render thread: loads or places {@code file} with Litematica and says how it went; a placement
   * closes the vault, so it shows at once.
   *
   * @param name the placement's name: the design's title, or the edited copy's
   */
  private void deliver(LitematicaAction action, Path file, SchematicFormat format, String name) {
    Outcome outcome =
        LitematicaButtons.deliver(minecraft, session.litematica(), action, file, format, name);
    if (outcome == null || closed) {
      return;
    }
    if (action == LitematicaAction.PLACE && outcome == Outcome.DONE) {
      closeVault();
    } else if (outcome == Outcome.DISABLED) {
      Screen current = minecraft.gui.screen();
      if (current == this) {
        addDetailPanel();
      } else if (current instanceof PreviewScreen preview) {
        preview.refresh();
      }
    }
  }

  /** The selected design's title as the panel shows it; empty when nothing is selected. */
  private String selectedTitle() {
    SchematicSummary schematic = selected;
    LoadedCatalog loaded = catalog;
    return schematic == null || loaded == null
        ? ""
        : DetailPanel.title(schematic, loaded.catalog().locale(), detail);
  }

  /** Leaves the vault from whichever of its screens is shown, closing the session. */
  private void closeVault() {
    openingChild = false;
    // To the world, even if the vault was opened from Litematica's menu: the placement must show.
    minecraft.gui.setScreen(null);
    if (!closed) {
      // A sub-screen was shown: the vault itself was not removed.
      closed = true;
      search.close();
      session.close();
    }
  }

  @Override
  public void openFolder(Path folder) {
    if (Files.isDirectory(folder)) {
      Blaze3D.openPath(folder);
    }
  }

  @Override
  public void openLink(URI uri) {
    openingChild = true;
    ConfirmLinkScreen.confirmLinkNow(this, uri, false);
  }

  private void openSettings() {
    openingChild = true;
    minecraft.gui.setScreen(
        new VaultSettingsScreen(
            SchematicVaultClient.settings(),
            SchematicVaultClient.keyMappings().openVault(),
            this::returnFromSettings));
  }

  /**
   * Back from the settings: unchanged, the vault shows again as it was; changed, the session's HTTP
   * client, download folder and 3D limit belong to the old settings, so the vault closes and opens
   * again with the new ones (the filter is kept by {@link FilterMemory}; the catalog loads from the
   * new address, never from the old address's cache).
   */
  private void returnFromSettings(VaultSettings saved) {
    if (saved.equals(session.settings())) {
      minecraft.gui.setScreen(this);
      return;
    }
    openingChild = false;
    if (!closed) {
      closed = true;
      search.close();
      session.close();
    }
    SchematicVaultClient.openVault(minecraft, parent);
  }

  private void retry() {
    status = CatalogStatus.DOWNLOADING;
    failure = null;
    updateFooter();
    rebuildWidgets();
    LoadedCatalog shown = catalog;
    session.catalog().refresh(shown == null ? null : shown.catalog(), minecraftLanguage, this);
  }

  @Override
  public void onCacheRead(@Nullable LoadedCatalog cached) {
    catalog = cached;
    status = cached == null ? CatalogStatus.DOWNLOADING : CatalogStatus.CHECKING;
    if (cached != null) {
      dropMissingCategory(cached);
    }
    applyFilter(false);
    rebuildWidgets();
  }

  @Override
  public void onProgress(int done, int total) {
    pagesDone = done;
    pageCount = total;
    if (catalog != null) {
      status = CatalogStatus.UPDATING;
    } else {
      downloadingMessage = Component.translatable("schematicvault.status.loading", done, total);
    }
    updateFooter();
  }

  @Override
  public void onCurrent() {
    status = CatalogStatus.CURRENT;
    updateFooter();
  }

  @Override
  public void onUpdated(LoadedCatalog updated) {
    catalog = updated;
    status = CatalogStatus.CURRENT;
    if (selected != null) {
      // Same id in the new revision: keep it selected with its new data.
      for (SchematicSummary schematic : updated.catalog().schematics()) {
        if (schematic.id().equals(selected.id())) {
          selected = schematic;
          break;
        }
      }
    }
    dropMissingCategory(updated);
    applyFilter(false);
    rebuildWidgets();
  }

  /** A remembered category this catalog no longer has: every category instead. */
  private void dropMissingCategory(LoadedCatalog shown) {
    if (CategoryTree.find(shown.categories(), filter.categoryPath()).isEmpty()) {
      filter = filter.withCategory("");
      FilterMemory.set(filter);
      bar.update(filter);
    }
  }

  @Override
  public void onFailed(FailureKind kind) {
    failure = kind;
    status = catalog == null ? CatalogStatus.UNAVAILABLE : CatalogStatus.OFFLINE;
    unavailableMessage =
        Component.translatable(
            kind == FailureKind.NETWORK
                ? "schematicvault.error.offline"
                : "schematicvault.error.server");
    updateFooter();
    if (catalog == null) {
      rebuildWidgets();
    }
  }

  /** The footer text for the current state, built when it changes (not per frame). */
  private void updateFooter() {
    LoadedCatalog shown = catalog;
    footer =
        switch (status) {
          case READING_CACHE, CHECKING -> CHECKING;
          case DOWNLOADING -> Component.empty();
          case UPDATING ->
              Component.translatable("schematicvault.status.updating", pagesDone, pageCount);
          case CURRENT ->
              shown == null
                  ? Component.empty()
                  : Component.translatable(
                      "schematicvault.status.count", matches.length, shown.index().size());
          case OFFLINE ->
              Component.translatable(
                  failure == FailureKind.NETWORK
                      ? "schematicvault.status.offline"
                      : "schematicvault.status.stale",
                  catalogDate(shown));
          case UNAVAILABLE -> Component.empty();
        };
  }

  private static String catalogDate(@Nullable LoadedCatalog shown) {
    String generatedAt = shown == null ? "" : shown.catalog().generatedAt();
    return generatedAt.length() >= ISO_DATE_LENGTH
        ? generatedAt.substring(0, ISO_DATE_LENGTH)
        : generatedAt;
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    // Under an open dropdown nothing else shows as hovered.
    boolean overDropdown = bar.isOverDropdown(mouseX, mouseY);
    super.extractRenderState(
        graphics, overDropdown ? -1 : mouseX, overDropdown ? -1 : mouseY, partialTick);
    bar.extractDecorations(graphics);
    int footerTop = height - MARGIN - FOOTER_HEIGHT;
    int footerTextY = footerTop + (FOOTER_HEIGHT - font.lineHeight) / 2 + 1;
    // Clipped before the Done button: a long translation never runs under it.
    graphics.enableScissor(MARGIN, footerTop, width - MARGIN - DONE_WIDTH - GAP, height - MARGIN);
    graphics.text(font, footer, MARGIN, footerTextY, STATUS_COLOR);
    graphics.disableScissor();
    boolean narrow = width < NARROW_WIDTH;
    if (list != null || !narrow || !showingDetail) {
      extractListMessage(graphics);
    }
    if (selected == null && !narrow && catalog != null) {
      graphics.centeredText(
          font,
          NO_SELECTION,
          detailLeft + detailWidth / 2,
          contentTop + contentHeight() / 2,
          STATUS_COLOR);
    }
    bar.extractDropdown(graphics, mouseX, mouseY);
  }

  /** The message over the list area: first download, no catalog, or no match. */
  private void extractListMessage(GuiGraphicsExtractor graphics) {
    int centerX = listLeft + listWidth / 2;
    int centerY = contentTop + contentHeight() / 2 - font.lineHeight;
    if (catalog == null) {
      if (status == CatalogStatus.UNAVAILABLE) {
        graphics.centeredText(font, unavailableMessage, centerX, centerY, ERROR_COLOR);
      } else if (status == CatalogStatus.DOWNLOADING) {
        graphics.centeredText(font, downloadingMessage, centerX, centerY, TEXT_COLOR);
      }
      return;
    }
    if (matches.length == 0) {
      graphics.centeredText(font, NO_RESULTS, centerX, centerY, STATUS_COLOR);
    }
  }

  /** An open dropdown takes the click first; one outside it closes it. */
  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    return bar.clickDropdown(event.x(), event.y()) || super.mouseClicked(event, doubleClick);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (bar.isDropdownOpen()) {
      // Outside the dropdown the wheel does nothing: the list under it stays where it is.
      bar.scrollDropdown(mouseX, mouseY, scrollY);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  /** The first Escape closes an open dropdown; the next one closes the vault. */
  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.isEscape() && bar.closeDropdown()) {
      return true;
    }
    return super.keyPressed(event);
  }

  /** Vanilla forwards only left-button drags to the focused widget: right-drags pan the preview. */
  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    PreviewWidget shown = preview;
    return shown != null && shown.forwardRightDrag(event, dx, dy)
        || super.mouseDragged(event, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    PreviewWidget shown = preview;
    return shown != null && shown.forwardRightRelease(event) || super.mouseReleased(event);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  /** Closes the session unless one of the vault's own sub-screens is taking over. */
  @Override
  public void removed() {
    if (!openingChild && !closed) {
      closed = true;
      search.close();
      session.close();
    }
  }

  /** Where the catalog stands. */
  private enum CatalogStatus {
    /** Reading the disk cache. */
    READING_CACHE,
    /** No cache: downloading the catalog for the first time. */
    DOWNLOADING,
    /** Cache shown; fetching the manifest. */
    CHECKING,
    /** Cache shown; downloading a new revision. */
    UPDATING,
    /** Showing the latest catalog. */
    CURRENT,
    /** Cache shown; the refresh failed. */
    OFFLINE,
    /** No cache and the download failed: error and Retry. */
    UNAVAILABLE
  }
}
