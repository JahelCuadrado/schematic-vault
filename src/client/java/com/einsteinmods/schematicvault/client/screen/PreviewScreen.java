package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.preview.BlockList;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.edit.BlockRule;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The 3D preview at nearly full screen: the design's title on top, the preview with the button that
 * shrinks it again in its corner, Back at the bottom. Same controls as in the vault ({@link
 * PreviewWidget}). It shows its host's scene with its camera ({@link PreviewController}), so
 * opening it builds nothing and the view carries over both ways. Its host is the vault or
 * Litematica's schematic browser ({@link PreviewHost}); it knows nothing of the catalog. Escape,
 * Back and the corner button return to the host, the vault keeping its selection, search and list
 * scroll.
 *
 * <p>Block editor: once the design is shown in 3D, a side panel ({@link BlockListPanel}, about a
 * third of the width) lists the blocks of the layers shown by type; a row opens {@link
 * BlockPickerScreen}, whose choice applies to the layers shown, and "Download edited" opens {@link
 * SaveEditedScreen}. Each change rebuilds the mesh on the preview's worker while the previous 3D
 * stays on screen with "Updating 3D…" over it.
 *
 * <p>Layers: once the design is shown in 3D, a vertical slider left of the preview ({@link
 * LayerSlider}) shows a range of its layers, with the range over the preview's top-left corner and
 * "All layers" at the bottom left. The block panel follows when the slider rests. The layers shown
 * stay across edits and sub-screens; leaving the view shows every layer again and frees the cut
 * mesh, so the vault's small preview never holds one.
 *
 * <p>With Litematica installed, "Load in Litematica" and "Place here" flank Back ({@link
 * LitematicaButtons}); an edited design is saved first (the host does the work: {@link
 * PreviewHost#sendShown}, {@link PreviewHost#saveEdited}). The footer is then one row of equal
 * slots: "All layers" (with the slider), Load, Back, Place.
 *
 * <p>Cost: a frame as the vault's preview (one blit while still) plus the panel's visible rows and,
 * with the slider, three sprites and one label. The scene's texture is made again at the new size
 * once on opening, on closing and on each resize.
 */
final class PreviewScreen extends Screen implements BlockListPanel.Actions {
  /** "⤡", over the preview's corner. */
  private static final Component EXIT_SYMBOL = Component.literal("⤡");

  private static final Component EXIT =
      Component.translatable("schematicvault.preview.fullscreen.exit");
  private static final Component UPDATING =
      Component.translatable("schematicvault.editor.updating");
  private static final Component SAVING = Component.translatable("schematicvault.editor.saving");
  private static final Component SAVE_FAILED =
      Component.translatable("schematicvault.editor.save_failed");
  private static final Component ALL_LAYERS =
      Component.translatable("schematicvault.preview.layers.all");

  private static final int MARGIN = 6;
  private static final int GAP = 4;
  private static final int TITLE_TOP = 8;
  private static final int BACK_WIDTH = 150;
  private static final int TITLE_COLOR = 0xFFFFFFFF;
  private static final int STATUS_COLOR = 0xFFA0A0A0;
  private static final int ERROR_COLOR = 0xFFFF6060;
  private static final int BAND_COLOR = 0xB0000000;
  private static final int BAND_PADDING = 2;

  /** Share of the content's width the block panel takes, in percent. */
  private static final int PANEL_SHARE_PERCENT = 35;

  private static final int PERCENT = 100;

  /** Narrowest preview left beside the panel. */
  private static final int MIN_PREVIEW_WIDTH = 160;

  /** Widest "All layers" button, left of Back. */
  private static final int ALL_LAYERS_WIDTH = 100;

  /** Footer slots with Litematica: Load, Back, Place. */
  private static final int LITEMATICA_SLOTS = 3;

  private final PreviewHost host;
  private final PreviewWidget.Backdrop backdrop;
  private final PreviewController previews;
  private final LitematicaBridge litematica;

  /** The title cut to the window's width, made in {@link #init} (not per frame). */
  private FormattedCharSequence shownTitle = FormattedCharSequence.EMPTY;

  /** The preview shown: the screen forwards its right-drags. */
  private @Nullable PreviewWidget preview;

  private @Nullable BlockListPanel panel;

  /** "All layers", shown with the layer slider; active while a cut is shown. */
  private @Nullable Button allLayers;

  /** The layers shown over the preview's corner, or null without the slider. */
  private @Nullable Component layerLabel;

  /** The label's width, measured when it changes (not per frame). */
  private int layerLabelWidth;

  private int previewTop;

  /** The block list's scroll, kept while the widgets are rebuilt after each change. */
  private double panelScroll;

  /** The last edited download of this view. */
  private DownloadState saveState = DownloadState.IDLE;

  /** "Saved: name", made once when the save finished. */
  private Component savedMessage = Component.empty();

  /** Where the panel's status line goes; the "Updating 3D…" band covers the preview's bottom. */
  private int statusX;

  private int statusY;
  private int previewLeft;
  private int previewRight;
  private int previewBottom;

  /**
   * @param title the design's title as the host shows it
   * @param backdrop what stands in for the 3D until it is ready
   * @param previews the host's preview, showing the file in 3D
   */
  PreviewScreen(
      PreviewHost host,
      Component title,
      PreviewWidget.Backdrop backdrop,
      PreviewController previews,
      LitematicaBridge litematica) {
    super(title);
    this.host = host;
    this.backdrop = backdrop;
    this.previews = previews;
    this.litematica = litematica;
  }

  @Override
  public void added() {
    // The click that opened a sub-screen was released there: no drag goes on here.
    setDragging(false);
  }

  @Override
  protected void init() {
    shownTitle = ComponentRenderUtils.clipText(title, font, width - MARGIN * 2);
    int contentTop = TITLE_TOP + font.lineHeight + GAP;
    int footerTop = height - MARGIN - Button.DEFAULT_HEIGHT;
    SchematicFormat format = previews.format();
    boolean withLitematica = format != null && LitematicaButtons.shown(litematica, format);
    int layerCount = previews.layerCount();
    boolean layered =
        previews.status() == PreviewController.Status.READY
            && layerCount > 1
            && width - MARGIN * 2 - LayerSlider.WIDTH - GAP >= MIN_PREVIEW_WIDTH;
    int backWidth = BACK_WIDTH;
    int backLeft = (width - backWidth) / 2;
    if (withLitematica) {
      // One row of equal slots: ["All layers"], Load, Back, Place.
      int slots = layered ? LITEMATICA_SLOTS + 1 : LITEMATICA_SLOTS;
      backWidth = Math.min(BACK_WIDTH, (width - MARGIN * 2 - GAP * (slots - 1)) / slots);
      int rowLeft = (width - (backWidth * slots + GAP * (slots - 1))) / 2;
      int slotsBeforeBack = layered ? 2 : 1;
      backLeft = rowLeft + slotsBeforeBack * (backWidth + GAP);
    }
    addRenderableWidget(
        Button.builder(CommonComponents.GUI_BACK, button -> onClose())
            .bounds(backLeft, footerTop, backWidth, Button.DEFAULT_HEIGHT)
            .build());
    int contentLeft = layered ? MARGIN + LayerSlider.WIDTH + GAP : MARGIN;
    int contentHeight = Math.max(1, footerTop - GAP - contentTop);
    allLayers = null;
    layerLabel = null;
    if (layered) {
      LayerSlider slider =
          new LayerSlider(
              contentHeight, previews, layerCount, this::onLayersChanged, this::onLayersSettled);
      slider.setPosition(MARGIN, contentTop);
      addRenderableWidget(slider);
      int allLeft = MARGIN;
      int allWidth =
          Math.max(1, Math.min(ALL_LAYERS_WIDTH, (width - BACK_WIDTH) / 2 - MARGIN - GAP));
      if (withLitematica) {
        allLeft = backLeft - 2 * (backWidth + GAP);
        allWidth = backWidth;
      }
      allLayers =
          addRenderableWidget(
              Button.builder(ALL_LAYERS, button -> showAllLayers())
                  .bounds(allLeft, footerTop, allWidth, Button.DEFAULT_HEIGHT)
                  .build());
      onLayersChanged();
    }
    if (format != null && withLitematica) {
      addLitematicaButtons(
          format, backLeft - GAP, backLeft + backWidth + GAP, backWidth, footerTop);
    }
    addContent(
        new ScreenRectangle(contentLeft, contentTop, width - MARGIN - contentLeft, contentHeight));
  }

  /** The slider moved (or the screen was built): the label and the button follow. */
  private void onLayersChanged() {
    LayerRange shown = previews.layerRange();
    int layerCount = previews.layerCount();
    Component label = LayerSlider.label(shown, layerCount);
    layerLabel = label;
    layerLabelWidth = font.width(label);
    Button button = allLayers;
    if (button != null) {
      button.active = !shown.covers(layerCount);
    }
  }

  /**
   * The slider rests: the material list counts the layers shown (see {@link PreviewController}).
   */
  private void onLayersSettled() {
    BlockListPanel shownPanel = panel;
    BlockList blocks = previews.shownBlockList();
    if (shownPanel != null && blocks != null) {
      shownPanel.show(blocks);
    }
  }

  private void showAllLayers() {
    previews.showAllLayers();
    rebuildWidgets();
  }

  /**
   * Fills the area between the title and Back: the preview, with the block panel on its right once
   * the design is shown in 3D and the window is wide enough for both.
   */
  private void addContent(ScreenRectangle area) {
    BlockList blocks = previews.shownBlockList();
    int panelWidth =
        Math.max(BlockListPanel.MIN_WIDTH, area.width() * PANEL_SHARE_PERCENT / PERCENT);
    boolean split = blocks != null && area.width() - panelWidth - GAP >= MIN_PREVIEW_WIDTH;
    int previewWidth = split ? area.width() - panelWidth - GAP : area.width();
    PreviewWidget widget =
        new PreviewWidget(font, previewWidth, area.height(), backdrop, title, previews);
    Layout framed = PreviewWidget.withCornerButton(widget, EXIT_SYMBOL, EXIT, this::onClose);
    framed.arrangeElements();
    framed.setPosition(area.left(), area.top());
    framed.visitWidgets(this::addRenderableWidget);
    preview = widget;
    previewLeft = area.left();
    previewTop = area.top();
    previewRight = area.left() + previewWidth;
    previewBottom = area.bottom();

    BlockListPanel oldPanel = panel;
    if (oldPanel != null) {
      panelScroll = oldPanel.scroll();
    }
    panel = null;
    if (split) {
      BlockListPanel built =
          new BlockListPanel(
              minecraft,
              font,
              new ScreenRectangle(previewRight + GAP, area.top(), panelWidth, area.height()),
              blocks,
              panelScroll,
              saveState.status() == DownloadState.Status.IN_PROGRESS,
              this);
      built.addTo(this::addRenderableWidget);
      panel = built;
      statusX = previewRight + GAP;
      statusY = built.statusY();
    }
  }

  /** Load to the left of Back (ending at {@code loadRight}), Place to its right. */
  private void addLitematicaButtons(
      SchematicFormat format, int loadRight, int placeLeft, int buttonWidth, int top) {
    List<Button> buttons =
        LitematicaButtons.create(
            litematica,
            format,
            buttonWidth * 2 + GAP,
            GAP,
            saveState.status() != DownloadState.Status.IN_PROGRESS,
            this::sendToLitematica);
    if (buttons.isEmpty()) {
      return;
    }
    Button load = buttons.get(0);
    Button place = buttons.get(1);
    load.setPosition(loadRight - load.getWidth(), top);
    place.setPosition(placeLeft, top);
    addRenderableWidget(load);
    addRenderableWidget(place);
  }

  /** The shown file, or its edited copy once saved (asking its name first), to Litematica. */
  private void sendToLitematica(LitematicaAction action) {
    if (previews.format() == null) {
      return;
    }
    if (!previews.edited()) {
      host.sendShown(action);
      return;
    }
    if (!host.sendLastEdited(action)) {
      host.askEditedName(this, name -> save(name, action));
    }
  }

  /** Called by the host when Litematica turned itself off: its buttons go away. */
  void refresh() {
    rebuildWidgets();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    graphics.centeredText(font, shownTitle, width / 2, TITLE_TOP, TITLE_COLOR);
    Component label = layerLabel;
    if (label != null) {
      int labelRight = previewLeft + layerLabelWidth + BAND_PADDING * 2;
      graphics.fill(
          previewLeft,
          previewTop,
          labelRight,
          previewTop + font.lineHeight + BAND_PADDING * 2,
          BAND_COLOR);
      graphics.text(
          font, label, previewLeft + BAND_PADDING, previewTop + BAND_PADDING + 1, TITLE_COLOR);
    }
    if (previews.rebuilding()) {
      int bandTop = previewBottom - font.lineHeight - BAND_PADDING * 2;
      graphics.fill(previewLeft, bandTop, previewRight, previewBottom, BAND_COLOR);
      graphics.centeredText(
          font,
          UPDATING,
          (previewLeft + previewRight) / 2,
          bandTop + BAND_PADDING + 1,
          TITLE_COLOR);
    }
    if (panel == null) {
      return;
    }
    switch (saveState.status()) {
      case IN_PROGRESS -> graphics.text(font, SAVING, statusX, statusY, STATUS_COLOR);
      case DONE -> graphics.text(font, savedMessage, statusX, statusY, STATUS_COLOR);
      case FAILED -> graphics.text(font, SAVE_FAILED, statusX, statusY, ERROR_COLOR);
      case IDLE -> {
        // Nothing saved in this view yet.
      }
    }
  }

  @Override
  public void pick(BlockList.Row row) {
    minecraft.gui.setScreen(
        new BlockPickerScreen(
            this,
            row.blockId(),
            row.block(),
            previews.replacementLayers(),
            newBlock -> previews.replace(row.blockId(), newBlock)));
  }

  @Override
  public void restore(BlockRule rule) {
    previews.restore(rule);
    rebuildWidgets();
  }

  @Override
  public void restoreAll() {
    previews.restoreAll();
    rebuildWidgets();
  }

  @Override
  public void downloadEdited() {
    host.askEditedName(this, name -> save(name, null));
  }

  /** Saves the edited copy as {@code name}, then hands it to Litematica if {@code then} says so. */
  private void save(String name, @Nullable LitematicaAction then) {
    saveState = DownloadState.IN_PROGRESS;
    host.saveEdited(name, then)
        .whenComplete(
            (saved, failure) -> {
              if (saved != null) {
                saveState = DownloadState.done(saved);
                savedMessage =
                    Component.translatable(
                        "schematicvault.editor.saved", saved.getFileName().toString());
              } else {
                saveState = DownloadState.failed(ApiException.kindOf(failure));
              }
              if (minecraft.gui.screen() == this) {
                rebuildWidgets();
              }
            });
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

  /** Back to the host with every layer shown: its small preview has no slider. */
  @Override
  public void onClose() {
    previews.showAllLayers();
    host.closePreview();
  }
}
