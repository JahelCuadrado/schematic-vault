package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.VaultSession;
import com.einsteinmods.schematicvault.client.cache.LocalThumbnailStore;
import com.einsteinmods.schematicvault.client.cache.RowThumbnails;
import com.einsteinmods.schematicvault.client.preview.ThumbnailGenerator;
import com.einsteinmods.schematicvault.core.config.VaultSettings;
import com.einsteinmods.schematicvault.core.thumbnails.ThumbnailPlan;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The thumbnails of Litematica's schematic browser, with no Litematica type: its "Generate
 * thumbnails" button, the confirmation it opens, the job it starts and the rows' textures. Nothing
 * is generated or written unless the player presses the button and confirms.
 *
 * <p>The rows look for thumbnails only if the cache folder exists, which only a generation creates:
 * until then the browser pays one disk check when it opens and one boolean per row drawn.
 *
 * <p>Once a row of the open folder draws its image the list takes the vault's row height ({@link
 * RowThumbnails#fitRows}) until another folder opens: a folder with no thumbnail stays as
 * Litematica draws it.
 *
 * <p>Lives as long as {@link BrowserPreview}. The job stops as soon as the browser is not on screen
 * ({@link #stop}, from the bridge's tick). Render thread.
 */
public final class BrowserThumbnails {
  /** The rows of a folder with thumbnails: those of the vault's list. */
  public static final int ROW_HEIGHT = SchematicList.ROW_HEIGHT;

  public static final int ROW_IMAGE_SIZE = SchematicList.THUMBNAIL_SIZE;

  /** {@code WidgetDirectoryEntry#render} starts a name 4 past its 12 px icon. */
  private static final int LITEMATICA_NAME_OFFSET = 16;

  /** What a name moves in those rows to sit beside the image, as in the vault's list. */
  public static final int ROW_TEXT_SHIFT =
      ROW_IMAGE_SIZE + SchematicList.TEXT_GAP - LITEMATICA_NAME_OFFSET;

  private static final String CONFIRM_FILES = "schematicvault.thumbnails.confirm.files";
  private static final String CONFIRM_TIME = "schematicvault.thumbnails.confirm.time";
  private static final String CONFIRM_TOO_BIG = "schematicvault.thumbnails.confirm.too_big";
  private static final String CONFIRM_LIMIT = "schematicvault.thumbnails.confirm.limit";
  private static final String CONFIRM_START = "schematicvault.thumbnails.confirm.start";
  private static final String GENERATE = "schematicvault.thumbnails.generate";
  private static final String COUNTING = "schematicvault.thumbnails.counting";
  private static final String NONE_MISSING = "schematicvault.thumbnails.none_missing";
  private static final String FAILED_SCAN = "schematicvault.thumbnails.failed";
  private static final String FINISHED = "schematicvault.thumbnails.finished";
  private static final String STOPPED = "schematicvault.thumbnails.stopped";
  private static final String TIME_SECONDS = "schematicvault.thumbnails.time.seconds";
  private static final String TIME_MINUTES = "schematicvault.thumbnails.time.minutes";
  private static final String PROGRESS_SEPARATOR = " / ";
  private static final String LINE_BREAK = "\n";
  private static final int SECONDS_PER_MINUTE = 60;
  private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

  private final Minecraft minecraft;
  private final Screen browser;
  private final LocalThumbnailStore store;
  private final RowThumbnails rows;
  private final String cancelLabel;
  private boolean showsRows;
  private boolean scanning;
  private boolean closed;
  private @Nullable Screen confirmation;
  private @Nullable ThumbnailGenerator generator;
  private int shownProcessed;
  private String status = "";

  BrowserThumbnails(Minecraft minecraft, Screen browser) {
    this.minecraft = minecraft;
    this.browser = browser;
    this.store = new LocalThumbnailStore(VaultSession.cacheDirectory());
    this.rows = new RowThumbnails(store, minecraft.getTextureManager(), minecraft);
    this.cancelLabel = CommonComponents.GUI_CANCEL.getString();
    this.showsRows = store.exists();
  }

  /** Whether the rows have thumbnails to look for. */
  public boolean showsRows() {
    return showsRows;
  }

  public RowThumbnails rows() {
    return rows;
  }

  /** Whether thumbnails are being made: the button cancels them. */
  public boolean running() {
    return generator != null;
  }

  public String cancelLabel() {
    return cancelLabel;
  }

  /** What to show next to the button; the same string until it changes. */
  public String status() {
    return status;
  }

  /** Whether {@code screen} is this browser's confirmation. */
  boolean confirming(@Nullable Screen screen) {
    return screen != null && screen == confirmation;
  }

  /** The button: cancels the job, else counts the files in {@code folder} and asks. */
  public void press(Path folder) {
    if (generator != null) {
      stop();
      return;
    }
    if (scanning) {
      return;
    }
    scanning = true;
    status = translated(COUNTING);
    VaultSettings settings = SchematicVaultClient.settings();
    ThumbnailGenerator.scan(folder, store, VaultSession.cacheDirectory(), settings.apiBaseUrl())
        .whenCompleteAsync(
            (scan, failure) -> scanned(scan, settings.previewBlockLimit()), minecraft);
  }

  private void scanned(ThumbnailGenerator.@Nullable Scan scan, int blockLimit) {
    scanning = false;
    if (closed || minecraft.gui.screen() != browser) {
      status = "";
      return;
    }
    if (scan == null) {
      status = translated(FAILED_SCAN);
      return;
    }
    ThumbnailPlan plan = scan.plan();
    if (plan.files() == 0) {
      status =
          plan.skipped() == 0 ? translated(NONE_MISSING) : translated(FINISHED, 0, plan.skipped());
      return;
    }
    status = "";
    Screen screen =
        new ConfirmScreen(
            accepted -> {
              confirmation = null;
              minecraft.gui.setScreen(browser);
              if (accepted) {
                start(scan, blockLimit);
              }
            },
            Component.translatable(GENERATE),
            confirmationText(plan, blockLimit),
            Component.translatable(CONFIRM_START),
            CommonComponents.GUI_CANCEL);
    confirmation = screen;
    minecraft.gui.setScreen(screen);
  }

  private static Component confirmationText(ThumbnailPlan plan, int blockLimit) {
    Component lines =
        Component.empty()
            .append(Component.translatable(CONFIRM_FILES, DetailPanel.count(plan.files())))
            .append(LINE_BREAK)
            .append(Component.translatable(CONFIRM_TIME, time(plan.estimatedSeconds())));
    if (plan.skipped() > 0) {
      lines =
          lines
              .copy()
              .append(LINE_BREAK)
              .append(
                  Component.translatable(
                      CONFIRM_TOO_BIG,
                      DetailPanel.count(plan.skipped()),
                      ThumbnailPlan.MAX_FILE_BYTES / BYTES_PER_MEGABYTE));
    }
    return lines
        .copy()
        .append(LINE_BREAK)
        .append(Component.translatable(CONFIRM_LIMIT, DetailPanel.count(blockLimit)));
  }

  private static Component time(int seconds) {
    return seconds < SECONDS_PER_MINUTE
        ? Component.translatable(TIME_SECONDS, seconds)
        : Component.translatable(TIME_MINUTES, seconds / SECONDS_PER_MINUTE);
  }

  private void start(ThumbnailGenerator.Scan scan, int blockLimit) {
    showsRows = true;
    shownProcessed = 0;
    status = progress(0, scan.items().size());
    generator =
        new ThumbnailGenerator(
            minecraft,
            scan,
            VaultSession.thumbnailDirectory(),
            blockLimit,
            rows::written,
            this::finished);
  }

  private void finished() {
    ThumbnailGenerator job = generator;
    if (job != null) {
      status = translated(FINISHED, job.made(), job.skipped());
      generator = null;
    }
  }

  /**
   * Called once per frame the browser draws its panel. No allocation of its own, but the progress
   * text when a file is done.
   */
  public void extract(GuiGraphicsExtractor graphics) {
    rows.nextFrame();
    ThumbnailGenerator job = generator;
    if (job == null) {
      return;
    }
    job.extract(graphics);
    if (job.processed() != shownProcessed) {
      shownProcessed = job.processed();
      status = progress(shownProcessed, job.total());
    }
  }

  /** Stops the job, if any; the thumbnails made stay. */
  public void stop() {
    ThumbnailGenerator job = generator;
    if (job != null) {
      generator = null;
      job.stop();
      status = translated(STOPPED, job.processed(), job.total());
    }
  }

  /** Stops the job and frees the rows' textures. */
  void close() {
    closed = true;
    stop();
    rows.release();
  }

  private static String progress(int processed, int total) {
    return processed + PROGRESS_SEPARATOR + total;
  }

  private static String translated(String key, Object... arguments) {
    return Component.translatable(key, arguments).getString();
  }
}
