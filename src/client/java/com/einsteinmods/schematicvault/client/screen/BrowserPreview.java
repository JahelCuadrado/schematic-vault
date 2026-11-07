package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.DesignEdits;
import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.download.SchematicFileWriter;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge.Outcome;
import com.einsteinmods.schematicvault.client.preview.EditedFile;
import com.einsteinmods.schematicvault.client.preview.PreviewCamera;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.files.PlacementNames;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.preview.BrowserPanelLayout;
import com.einsteinmods.schematicvault.core.preview.TurnTable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The 3D of the file selected in Litematica's schematic browser, with no Litematica type: its
 * bridge ({@code LitematicaIntegration}) tells it the selection and where the panel is. It exists
 * only while that screen (or the big view opened from it) is shown, holding a preview of its own
 * without an API client, whose mesh, GPU buffers and read files go when it closes.
 *
 * <p>The small view turns by itself with no input ({@link TurnTable}); "Enlarge and edit" opens
 * {@link PreviewScreen} on the same scene. An edited copy is saved next to the original: Litematica
 * scans that folder again when its screen comes back, so the copy is in the list the player left.
 *
 * <p>Cost, only with that screen open: per frame one backdrop rectangle and one reused
 * picture-in-picture state, or the lines of a message; the mesh redraws at most 30 times a second,
 * and every other frame is one blit. No allocation of the mod's own per frame. Render thread.
 */
public final class BrowserPreview implements PreviewHost {
  /** The preview's own backdrop color ({@code PreviewRenderer}): the slot blends into it. */
  private static final int BACKDROP = 0xFF1E1E24;

  private static final int TEXT_COLOR = 0xFFFFFFFF;

  private final Minecraft minecraft;
  private final Screen browser;
  private final PreviewController previews;
  private final EditedCopies copies;
  private final TurnTable turnTable = new TurnTable();
  private final BrowserThumbnails thumbnails;

  /** The bridge, made on first use. */
  private @Nullable LitematicaBridge litematica;

  /** The browser's selected entry, compared by identity on each frame. */
  private @Nullable Object selection;

  /** The big view is open, or a screen of its own over it. */
  private boolean editing;

  private @Nullable BrowserPanelLayout layout;
  private int layoutX;
  private int layoutY;
  private int layoutHeight;

  /** The lines shown in place of the 3D, wrapped once per status. */
  private List<FormattedCharSequence> message = List.of();

  private PreviewController.@Nullable Status messageStatus;

  public BrowserPreview(Minecraft minecraft, Screen browser) {
    this.minecraft = minecraft;
    this.browser = browser;
    this.previews =
        PreviewController.forLocalFiles(
            minecraft, SchematicVaultClient.settings().previewBlockLimit(), new DesignEdits());
    this.copies = new EditedCopies(minecraft, previews, this::writeNextToOriginal, this::deliver);
    this.thumbnails = new BrowserThumbnails(minecraft, browser);
    previews.camera().fitWholeTurn(true);
  }

  public Screen browser() {
    return browser;
  }

  /** The thumbnails of the browser's rows. */
  public BrowserThumbnails thumbnails() {
    return thumbnails;
  }

  /** Whether the player is still in the browser or in the big view opened from it. */
  public boolean stillShown(@Nullable Screen current) {
    return current == browser
        || thumbnails.confirming(current)
        || editing
            && (current instanceof PreviewScreen
                || current instanceof BlockPickerScreen
                || current instanceof SaveEditedScreen);
  }

  /**
   * Whether {@code entry} is another selection than the last one given here; no allocation, so it
   * is safe per frame.
   */
  public boolean selectionChanged(@Nullable Object entry) {
    if (entry == selection) {
      return false;
    }
    selection = entry;
    return true;
  }

  /** Shows {@code file}: the latest selection wins, nothing piles up. */
  public void show(Path file, SchematicFormat format) {
    messageStatus = null;
    previews.showLocal(file, format);
  }

  /** The selection is no schematic: nothing to show. */
  public void showNothing() {
    messageStatus = null;
    previews.release();
  }

  /** Whether a schematic file is selected: the panel's button and the slot are in use. */
  public boolean showsFile() {
    return previews.format() != null;
  }

  /** The panel's layout, made again only when it changes. */
  public BrowserPanelLayout layout(int x, int y, int height) {
    BrowserPanelLayout current = layout;
    if (current == null || x != layoutX || y != layoutY || height != layoutHeight) {
      current = BrowserPanelLayout.of(x, y, height);
      layout = current;
      layoutX = x;
      layoutY = y;
      layoutHeight = height;
    }
    return current;
  }

  /** Whether the panel's button offers to show a file over the block limit. */
  public boolean offersShowAnyway() {
    return previews.status() == PreviewController.Status.OVER_LIMIT;
  }

  /** Whether the panel's button does anything now. */
  public boolean buttonActive() {
    return offersShowAnyway() || previews.status() == PreviewController.Status.READY;
  }

  /** The panel's button: shows a file over the limit, or opens the big view. */
  public void pressButton() {
    switch (previews.status()) {
      case OVER_LIMIT -> previews.showAnyway();
      case READY -> enlarge();
      case NONE, LOADING, FAILED_DOWNLOAD, FAILED_READ, TOO_LARGE -> {
        // The button is off.
      }
    }
  }

  /**
   * Draws the slot: the backdrop that hides Litematica's preview image, then the design turning, or
   * what the preview is doing. Called once per frame the browser draws its panel.
   */
  public void extract(GuiGraphicsExtractor graphics, BrowserPanelLayout area) {
    if (!area.showsPreview()) {
      return;
    }
    graphics.fill(
        area.coverLeft(), area.coverTop(), area.coverRight(), area.coverBottom(), BACKDROP);
    PreviewController.Status status = previews.status();
    if (status == PreviewController.Status.READY) {
      PreviewCamera camera = previews.camera();
      float turn = turnTable.advance(Util.getMillis());
      if (turn != 0.0F) {
        camera.spin(turn);
      }
      previews
          .renderState()
          .place(
              area.slotLeft(),
              area.slotTop(),
              area.slotRight(),
              area.slotBottom(),
              graphics.scissorStack.peek());
      graphics.guiRenderState.addPicturesInPictureState(previews.renderState());
      return;
    }
    int width = area.slotRight() - area.slotLeft();
    List<FormattedCharSequence> lines = message(status, width);
    int lineHeight = minecraft.font.lineHeight;
    int top = (area.slotTop() + area.slotBottom() - lines.size() * lineHeight) / 2;
    int center = area.slotLeft() + width / 2;
    for (int line = 0; line < lines.size(); line++) {
      graphics.centeredText(
          minecraft.font, lines.get(line), center, top + line * lineHeight, TEXT_COLOR);
    }
  }

  private List<FormattedCharSequence> message(PreviewController.Status status, int width) {
    if (status != messageStatus) {
      messageStatus = status;
      Component text = messageText(status);
      message = text == null ? List.of() : minecraft.font.split(text, width);
    }
    return message;
  }

  private @Nullable Component messageText(PreviewController.Status status) {
    return switch (status) {
      case NONE, READY -> null;
      case LOADING -> Component.translatable("schematicvault.preview.loading");
      case OVER_LIMIT ->
          Component.translatable(
              "schematicvault.preview.over_limit",
              DetailPanel.count(previews.localBlocks()),
              DetailPanel.count(previews.blockLimit()));
      case FAILED_DOWNLOAD, FAILED_READ ->
          Component.translatable("schematicvault.preview.failed.read");
      case TOO_LARGE -> Component.translatable("schematicvault.preview.too_large");
    };
  }

  private void enlarge() {
    Path file = previews.localFile();
    if (file == null) {
      return;
    }
    editing = true;
    PreviewCamera camera = previews.camera();
    camera.reset();
    camera.fitWholeTurn(false);
    minecraft.gui.setScreen(
        new PreviewScreen(
            this,
            Component.literal(file.getFileName().toString()),
            (graphics, x, y, size) -> {
              // A local file has no thumbnail: the message over the preview is enough.
            },
            previews,
            litematica()));
  }

  private LitematicaBridge litematica() {
    LitematicaBridge bridge = litematica;
    if (bridge == null) {
      bridge = LitematicaBridge.create();
      litematica = bridge;
    }
    return bridge;
  }

  @Override
  public void closePreview() {
    editing = false;
    PreviewCamera camera = previews.camera();
    camera.reset();
    camera.fitWholeTurn(true);
    minecraft.gui.setScreen(browser);
  }

  @Override
  public void sendShown(LitematicaAction action) {
    Path file = previews.localFile();
    SchematicFormat format = previews.format();
    if (file != null && format != null) {
      // No title: the placement takes the file's name.
      deliver(action, file, format, "");
    }
  }

  @Override
  public boolean sendLastEdited(LitematicaAction action) {
    return copies.sendLast(action);
  }

  @Override
  public void askEditedName(Screen returnTo, Consumer<String> onSave) {
    Path file = previews.localFile();
    SchematicFormat format = previews.editedFormat();
    if (file == null || format == null) {
      return;
    }
    String title = PlacementNames.of("", file.getFileName().toString());
    minecraft.gui.setScreen(
        new SaveEditedScreen(
            returnTo,
            SaveEditedScreen.editedName(title),
            SaveEditedScreen.formatLine(format),
            onSave));
  }

  @Override
  public CompletableFuture<Path> saveEdited(String name, @Nullable LitematicaAction then) {
    return copies.save(name, "", then);
  }

  /** Writes an edited copy into the original's folder, which is the one the browser shows. */
  private CompletableFuture<Path> writeNextToOriginal(EditedFile edited, String name) {
    Path file = previews.localFile();
    Path folder = file == null ? null : file.getParent();
    if (folder == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("no file shown"));
    }
    return new SchematicFileWriter(folder).saveEdited(edited, name);
  }

  /** Loads or places {@code file}; a placement leaves for the world, so it shows at once. */
  private void deliver(LitematicaAction action, Path file, SchematicFormat format, String name) {
    Outcome outcome =
        LitematicaButtons.deliver(minecraft, litematica(), action, file, format, name);
    if (action == LitematicaAction.PLACE && outcome == Outcome.DONE) {
      minecraft.gui.setScreen(null);
    } else if (outcome == Outcome.DISABLED
        && minecraft.gui.screen() instanceof PreviewScreen preview) {
      preview.refresh();
    }
  }

  /** Frees the mesh, the GPU buffers, the read files and the rows' thumbnails. */
  public void close() {
    thumbnails.close();
    previews.close();
  }
}
