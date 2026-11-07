package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.VaultSession.DetailView;
import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.catalog.BlockSize;
import com.einsteinmods.schematicvault.core.catalog.LocaleTexts;
import com.einsteinmods.schematicvault.core.catalog.MarkdownText;
import com.einsteinmods.schematicvault.core.catalog.SchematicDetail;
import com.einsteinmods.schematicvault.core.catalog.SchematicFile;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import com.einsteinmods.schematicvault.core.files.FileSize;
import com.einsteinmods.schematicvault.core.net.WebLinks;
import com.einsteinmods.schematicvault.core.volume.StandInBlocks;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Builds the detail panel of the selected design as vanilla widgets: the 3D preview on top (outside
 * the scroll, so the wheel zooms it and dragging never scrolls) with its full-screen button, then a
 * scrolling column with the preview's notices, title, authors, size, block count, Minecraft
 * version, license, tags, description, source with its link, the files with their preview and
 * download buttons (plus "Load in Litematica" and "Place here" when Litematica is installed), and
 * the author's notes. Texts are the game language's when the API translates them ({@link
 * LocaleTexts}, the record's translations). Built again only when something it shows changes
 * (selection, record loaded, a download's or the preview's state, a resize), never per frame.
 */
final class DetailPanel {
  /** Smallest preview: the API's thumbnails are 96 px. */
  static final int PREVIEW_SIZE = 96;

  /** Tallest preview, in GUI pixels: past it the details would get too little room. */
  private static final int MAX_PREVIEW_HEIGHT = 240;

  /** Share of the panel's height the preview takes, in percent. */
  private static final int PREVIEW_SHARE_PERCENT = 45;

  private static final int PERCENT = 100;
  private static final int SPACING = 3;
  private static final int SCROLLBAR_RESERVE = 10;
  private static final int MAX_BUTTON_WIDTH = 200;
  private static final String NAME_SEPARATOR = ", ";
  private static final String BLOCK_COUNT_FORMAT = "%,d";

  /** Mods named in the "Uses blocks from mods" line; more end in {@link #MORE}. */
  private static final int MAX_MOD_NAMES = 4;

  /** Ids listed in that line's tooltip; more end in {@link #MORE}. */
  private static final int MAX_TOOLTIP_IDS = 12;

  private static final String MORE = "…";
  private static final String SHOWN_AS_ARROW = " → ";
  private static final String NEW_LINE = "\n";

  /** "⤢", over the preview's corner. */
  private static final Component FULL_SCREEN_SYMBOL = Component.literal("\u2922");

  private static final Component FULL_SCREEN =
      Component.translatable("schematicvault.preview.fullscreen");

  private DetailPanel() {}

  /** What the panel's buttons do; the screen implements them. */
  interface Actions {
    void download(SchematicFile file);

    /** Loads or places the file with Litematica (downloading or saving it first). */
    void sendToLitematica(SchematicFile file, LitematicaAction action);

    void openFolder(Path folder);

    void openLink(URI uri);

    /** Previews another of the design's files. */
    void previewFile(SchematicFile file);

    /** Loads the 3D preview of a design over the block limit. */
    void showPreviewAnyway();

    /** Shows the 3D preview full screen. */
    void showFullScreen();
  }

  /** The record's state for the selected design. */
  enum RecordState {
    LOADING,
    LOADED,
    FAILED,
    /** The API no longer has the record (HTTP 404). */
    GONE
  }

  /**
   * The panel for {@code schematic} at ({@code x}, {@code y}), {@code width} by {@code height}.
   *
   * @param previews the preview, already showing {@code schematic}
   * @param locale the catalog's texts in the game language
   * @param view its record once loaded, else null
   * @param downloads the state of each file
   * @param litematica the bridge that decides whether the Litematica buttons show
   */
  static Layout build(
      Minecraft minecraft,
      Font font,
      int x,
      int y,
      int width,
      int height,
      ThumbnailCache thumbnails,
      PreviewController previews,
      SchematicSummary schematic,
      LocaleTexts locale,
      RecordState recordState,
      @Nullable DetailView view,
      Function<SchematicFile, DownloadState> downloads,
      LitematicaBridge litematica,
      Actions actions) {
    int contentWidth = Math.max(1, width - SCROLLBAR_RESERVE);
    int buttonWidth = Math.min(contentWidth, MAX_BUTTON_WIDTH);
    int previewHeight = previewHeight(height);
    LinearLayout panel = LinearLayout.vertical().spacing(SPACING);
    panel.addChild(
        preview(font, contentWidth, previewHeight, thumbnails, schematic, previews, actions));
    LinearLayout column = LinearLayout.vertical().spacing(SPACING);
    addPreviewNotes(column, font, contentWidth, buttonWidth, schematic, previews, actions);
    String language = locale.language();
    String title = title(schematic, locale, view);
    column.addChild(
        text(font, contentWidth, Component.literal(title).withStyle(ChatFormatting.BOLD)));
    column.addChild(text(font, contentWidth, authors(schematic, view)));
    for (Component fact : facts(schematic, view)) {
      column.addChild(text(font, contentWidth, fact));
    }
    if (!schematic.tags().isEmpty()) {
      column.addChild(text(font, contentWidth, tags(schematic, locale)));
    }
    switch (recordState) {
      case LOADING ->
          column.addChild(text(font, contentWidth, gray("schematicvault.detail.loading")));
      case FAILED ->
          column.addChild(text(font, contentWidth, error("schematicvault.detail.failed")));
      case GONE -> column.addChild(text(font, contentWidth, error("schematicvault.detail.gone")));
      case LOADED -> {
        if (view != null) {
          SchematicDetail detail = view.detail();
          detail
              .description(language)
              .ifPresent(
                  description ->
                      column.addChild(text(font, contentWidth, Component.literal(description))));
          addRecord(
              column,
              font,
              contentWidth,
              buttonWidth,
              view,
              previews.file(),
              downloads,
              litematica,
              actions);
          detail
              .authorNotes(language)
              .ifPresent(notes -> addAuthorNotes(column, font, contentWidth, notes));
        }
      }
    }
    int detailsHeight = Math.max(1, height - previewHeight - SPACING);
    ScrollableLayout scrollable =
        new ScrollableLayout(
            minecraft, column, detailsHeight, ScrollableLayout.ReserveStrategy.RIGHT);
    scrollable.setMinWidth(contentWidth);
    scrollable.setMinHeight(detailsHeight);
    panel.addChild(scrollable);
    panel.arrangeElements();
    panel.setX(x);
    panel.setY(y);
    return panel;
  }

  /**
   * The preview's height: {@value #PREVIEW_SHARE_PERCENT} % of the panel, at least the thumbnail
   * size (or half the panel when it is shorter than two of them), at most {@value
   * #MAX_PREVIEW_HEIGHT} px.
   */
  private static int previewHeight(int height) {
    int smallest = Math.min(PREVIEW_SIZE, height / 2);
    return Math.max(
        1,
        Math.min(MAX_PREVIEW_HEIGHT, Math.max(smallest, height * PREVIEW_SHARE_PERCENT / PERCENT)));
  }

  /** The title shown: the record's own translation once loaded, the catalog locale's until then. */
  static String title(SchematicSummary schematic, LocaleTexts locale, @Nullable DetailView view) {
    String title = locale.title(schematic);
    return view == null ? title : view.detail().title(locale.language()).orElse(title);
  }

  /** The preview slot with its full-screen button: the only place that creates it. */
  private static Layout preview(
      Font font,
      int contentWidth,
      int height,
      ThumbnailCache thumbnails,
      SchematicSummary schematic,
      PreviewController previews,
      Actions actions) {
    return PreviewWidget.withCornerButton(
        new PreviewWidget(
            font,
            contentWidth,
            height,
            PreviewWidget.thumbnail(thumbnails, schematic.thumbnailPngUrl()),
            Component.literal(schematic.title()),
            previews),
        FULL_SCREEN_SYMBOL,
        FULL_SCREEN,
        actions::showFullScreen);
  }

  /**
   * The preview's notices: the block limit with its button, the controls, blocks of mods the player
   * lacks (drawn as similar vanilla blocks).
   */
  private static void addPreviewNotes(
      LinearLayout column,
      Font font,
      int contentWidth,
      int buttonWidth,
      SchematicSummary schematic,
      PreviewController previews,
      Actions actions) {
    switch (previews.status()) {
      case OVER_LIMIT -> {
        column.addChild(
            text(
                font,
                contentWidth,
                Component.translatable(
                        "schematicvault.preview.over_limit",
                        count(schematic.blockCount()),
                        count(previews.blockLimit()))
                    .withStyle(ChatFormatting.GRAY)));
        column.addChild(
            Button.builder(
                    Component.translatable("schematicvault.preview.show_anyway"),
                    button -> actions.showPreviewAnyway())
                .width(buttonWidth)
                .build());
      }
      case READY -> column.addChild(text(font, contentWidth, gray("schematicvault.preview.hint")));
      case NONE, LOADING, FAILED_DOWNLOAD, FAILED_READ, TOO_LARGE -> {
        // The preview itself says what it is doing.
      }
    }
    SortedMap<String, Block> standIns = previews.standIns();
    if (!standIns.isEmpty()) {
      MultiLineTextWidget notice = text(font, contentWidth, standInNotice(standIns));
      notice.setTooltip(Tooltip.create(standInTooltip(standIns)));
      column.addChild(notice);
    }
  }

  /**
   * "Uses blocks from mods: Connected Glass, Waystones" and/or "Uses old blocks without a modern
   * equivalent".
   */
  private static Component standInNotice(SortedMap<String, Block> standIns) {
    List<String> mods =
        StandInBlocks.firstNames(StandInBlocks.modNames(standIns.keySet()), MAX_MOD_NAMES, MORE);
    MutableComponent notice = Component.empty().withStyle(ChatFormatting.GOLD);
    if (!mods.isEmpty()) {
      notice.append(
          Component.translatable(
              "schematicvault.preview.modded", String.join(NAME_SEPARATOR, mods)));
    }
    if (StandInBlocks.hasOldBlocks(standIns.keySet())) {
      if (!mods.isEmpty()) {
        notice.append(NEW_LINE);
      }
      notice.append(Component.translatable("schematicvault.preview.old_blocks"));
    }
    return notice;
  }

  /** Each id and the vanilla block drawn for it, under a line saying downloads keep the ids. */
  private static Component standInTooltip(SortedMap<String, Block> standIns) {
    MutableComponent tooltip =
        Component.translatable("schematicvault.preview.stand_ins").withStyle(ChatFormatting.GRAY);
    int listed = 0;
    for (Map.Entry<String, Block> standIn : standIns.entrySet()) {
      tooltip.append(NEW_LINE);
      if (listed == MAX_TOOLTIP_IDS) {
        tooltip.append(MORE);
        break;
      }
      tooltip
          .append(
              Component.literal(standIn.getKey() + SHOWN_AS_ARROW).withStyle(ChatFormatting.WHITE))
          .append(standIn.getValue().getName().withStyle(ChatFormatting.WHITE));
      listed++;
    }
    return tooltip;
  }

  static String count(long value) {
    return String.format(Locale.ROOT, BLOCK_COUNT_FORMAT, value);
  }

  private static void addRecord(
      LinearLayout column,
      Font font,
      int contentWidth,
      int buttonWidth,
      DetailView view,
      @Nullable SchematicFile previewed,
      Function<SchematicFile, DownloadState> downloads,
      LitematicaBridge litematica,
      Actions actions) {
    SchematicDetail detail = view.detail();
    if (detail.sourceName() != null) {
      column.addChild(
          text(
              font,
              contentWidth,
              Component.translatable("schematicvault.detail.source", detail.sourceName())));
    }
    Optional<URI> sourceLink =
        detail.sourceUrl() == null ? Optional.empty() : WebLinks.parse(detail.sourceUrl());
    sourceLink.ifPresent(
        uri ->
            column.addChild(
                Button.builder(
                        Component.translatable("schematicvault.detail.open_source"),
                        button -> actions.openLink(uri))
                    .width(buttonWidth)
                    .build()));
    Optional<SchematicFile> primary = detail.primaryFile();
    if (primary.isEmpty()) {
      column.addChild(text(font, contentWidth, gray("schematicvault.detail.no_files")));
      return;
    }
    addDownload(
        column, font, contentWidth, buttonWidth, primary.get(), downloads, litematica, actions);
    if (detail.files().size() == 1) {
      column.addChild(
          text(font, contentWidth, fileLine(primary.get()).withStyle(ChatFormatting.GRAY)));
      return;
    }
    column.addChild(
        text(font, contentWidth, Component.translatable("schematicvault.detail.files")));
    for (SchematicFile file : detail.files()) {
      column.addChild(text(font, contentWidth, fileLine(file).withStyle(ChatFormatting.GRAY)));
      boolean shown = previewed != null && previewed.sha256().equals(file.sha256());
      Button preview =
          Button.builder(
                  Component.translatable(
                      shown
                          ? "schematicvault.preview.file.current"
                          : "schematicvault.preview.file"),
                  button -> actions.previewFile(file))
              .width(buttonWidth)
              .build();
      preview.active = !shown;
      column.addChild(preview);
      addDownload(column, font, contentWidth, buttonWidth, file, downloads, litematica, actions);
    }
  }

  /**
   * A file's button (and its failure, if the last try failed), then its Litematica buttons side by
   * side when Litematica is installed.
   */
  private static void addDownload(
      LinearLayout column,
      Font font,
      int contentWidth,
      int buttonWidth,
      SchematicFile file,
      Function<SchematicFile, DownloadState> downloads,
      LitematicaBridge litematica,
      Actions actions) {
    DownloadState state = downloads.apply(file);
    Button button =
        switch (state.status()) {
          case IN_PROGRESS -> {
            Button busy =
                Button.builder(
                        Component.translatable("schematicvault.download.progress"), pressed -> {})
                    .width(buttonWidth)
                    .build();
            busy.active = false;
            yield busy;
          }
          case DONE -> {
            Path saved = state.saved();
            Path folder = saved == null ? null : saved.getParent();
            yield Button.builder(
                    Component.translatable("schematicvault.download.done"),
                    pressed -> {
                      if (folder != null) {
                        actions.openFolder(folder);
                      }
                    })
                .width(buttonWidth)
                .build();
          }
          case IDLE, FAILED ->
              Button.builder(
                      Component.translatable("schematicvault.download"),
                      pressed -> actions.download(file))
                  .width(buttonWidth)
                  .build();
        };
    column.addChild(button);
    FailureKind failure = state.failure();
    if (failure != null) {
      column.addChild(text(font, contentWidth, error(failureKey(failure))));
    }
    List<Button> litematicaButtons =
        LitematicaButtons.create(
            litematica,
            file.format(),
            buttonWidth,
            SPACING,
            state.status() != DownloadState.Status.IN_PROGRESS,
            action -> actions.sendToLitematica(file, action));
    if (!litematicaButtons.isEmpty()) {
      LinearLayout row = LinearLayout.horizontal().spacing(SPACING);
      litematicaButtons.forEach(row::addChild);
      column.addChild(row);
    }
  }

  static String failureKey(FailureKind failure) {
    return switch (failure) {
      case NETWORK -> "schematicvault.download.failed.network";
      case SERVER -> "schematicvault.download.failed.server";
      case CHECKSUM -> "schematicvault.download.failed.checksum";
      case DISK -> "schematicvault.download.failed.disk";
    };
  }

  /** The author's notes as plain text under their heading. */
  private static void addAuthorNotes(
      LinearLayout column, Font font, int contentWidth, String notes) {
    String plain = MarkdownText.plain(notes);
    if (plain.isEmpty()) {
      return;
    }
    column.addChild(
        text(
            font,
            contentWidth,
            Component.translatable("schematicvault.detail.notes").withStyle(ChatFormatting.BOLD)));
    column.addChild(
        text(font, contentWidth, Component.literal(plain).withStyle(ChatFormatting.GRAY)));
  }

  /** {@code Tags: a, b} with the locale's tag names. */
  private static Component tags(SchematicSummary schematic, LocaleTexts locale) {
    List<String> names = new ArrayList<>(schematic.tags().size());
    for (String tag : schematic.tags()) {
      names.add(locale.tagName(tag));
    }
    return Component.translatable("schematicvault.detail.tags", String.join(NAME_SEPARATOR, names))
        .withStyle(ChatFormatting.GRAY);
  }

  /** Authors from the record once loaded (it credits roles too), else from the catalog. */
  private static Component authors(SchematicSummary schematic, @Nullable DetailView view) {
    List<String> names = new ArrayList<>();
    if (view != null) {
      view.detail().authors().forEach(author -> names.add(author.name()));
    }
    if (names.isEmpty()) {
      names.addAll(schematic.authors());
    }
    Component line =
        names.isEmpty()
            ? Component.translatable("schematicvault.detail.unknown_author")
            : Component.translatable(
                "schematicvault.detail.by", String.join(NAME_SEPARATOR, names));
    return line.copy().withStyle(ChatFormatting.GRAY);
  }

  private static List<Component> facts(SchematicSummary schematic, @Nullable DetailView view) {
    List<Component> facts = new ArrayList<>();
    BlockSize size = schematic.size();
    if (size.isKnown()) {
      facts.add(Component.translatable("schematicvault.detail.size", size.x(), size.y(), size.z()));
    }
    if (schematic.blockCount() > 0) {
      facts.add(
          Component.translatable("schematicvault.detail.blocks", count(schematic.blockCount())));
    }
    String version = minecraftVersion(schematic, view);
    if (version != null) {
      facts.add(Component.translatable("schematicvault.detail.version", version));
    }
    Component license =
        schematic.license() == null
            ? Component.translatable("schematicvault.detail.license.none")
            : Component.literal(schematic.license());
    facts.add(Component.translatable("schematicvault.detail.license", license));
    return facts;
  }

  /**
   * The summary's Minecraft version (catalog pages), else the record's primary file's once loaded:
   * the compact index has none.
   */
  private static @Nullable String minecraftVersion(
      SchematicSummary schematic, @Nullable DetailView view) {
    if (schematic.minecraftVersion() != null || view == null) {
      return schematic.minecraftVersion();
    }
    return view.detail().primaryFile().map(SchematicFile::minecraftVersion).orElse(null);
  }

  /** {@code name · format · size}. */
  private static MutableComponent fileLine(SchematicFile file) {
    FileSize size = FileSize.of(file.sizeBytes());
    String unitKey =
        switch (size.unit()) {
          case BYTES -> "schematicvault.size.bytes";
          case KILOBYTES -> "schematicvault.size.kilobytes";
          case MEGABYTES -> "schematicvault.size.megabytes";
        };
    return Component.translatable(
        "schematicvault.detail.file",
        file.fileName(),
        file.format().label(),
        Component.translatable(unitKey, size.amount()));
  }

  private static MultiLineTextWidget text(Font font, int width, Component message) {
    return new MultiLineTextWidget(message, font).setMaxWidth(width);
  }

  private static Component gray(String key) {
    return Component.translatable(key).withStyle(ChatFormatting.GRAY);
  }

  private static Component error(String key) {
    return Component.translatable(key).withStyle(ChatFormatting.RED);
  }
}
