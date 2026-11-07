package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The thumbnails of the rows on screen in a Litematica list, with no Litematica type. A row is
 * known by its list entry, an object that lives as long as the list does, so the lookup each row
 * makes each frame is one identity hash, with no allocation and no disk. Its first lookup finds its
 * PNG on the I/O pool, at most {@link #MAX_LOADING} at a time, and reads it into a texture. At most
 * {@link #MAX_ROWS} rows are held (about 37 KB of GPU memory each): a new row frees those no longer
 * drawn, and past that it simply keeps Litematica's icon. {@link #release} frees all. Render
 * thread, apart from what it hands to the pool.
 */
public final class RowThumbnails {
  /** More rows than a 4K window shows at the smallest GUI scale. */
  private static final int MAX_ROWS = 192;

  private static final int MAX_LOADING = 4;
  private static final String TEXTURE_PATH = "local_thumbnail/";

  private final LocalThumbnailStore store;
  private final TextureManager textures;
  private final Executor renderThread;
  private final Map<Object, Row> rows = new IdentityHashMap<>();
  private int loading;
  private int nextTextureId;
  private long frame;
  private boolean imageDrawn;
  private boolean tallRows;
  private @Nullable Object shownFolder;
  private boolean released;

  public RowThumbnails(LocalThumbnailStore store, TextureManager textures, Executor renderThread) {
    this.store = store;
    this.textures = textures;
    this.renderThread = renderThread;
  }

  /** Called once per frame: the rows drawn since the last call are the ones on screen. */
  public void nextFrame() {
    frame++;
  }

  /** A row on screen has just drawn its image. */
  public void markImageDrawn() {
    imageDrawn = true;
  }

  /** Whether a row has drawn its image since this list or folder opened. */
  public boolean imageDrawn() {
    return imageDrawn;
  }

  /** Whether the list has the taller rows. */
  public boolean tallRows() {
    return tallRows;
  }

  /**
   * Follows the folder shown, once per frame the list draws: the rows are tall from the frame after
   * one drew its image, and go back when the folder changes. A list found tall stays so: this
   * object is made again after a dialog, for the same list and folder. Compares by identity, as the
   * list keeps one path per folder. No allocation.
   *
   * @param folder the list's current folder
   * @param listIsTall whether the list has the taller rows now
   * @return whether it should have them
   */
  public boolean fitRows(Object folder, boolean listIsTall) {
    if (shownFolder == null) {
      tallRows = listIsTall;
    } else if (folder != shownFolder) {
      tallRows = false;
      imageDrawn = false;
    }
    shownFolder = folder;
    tallRows |= imageDrawn;
    return tallRows;
  }

  /** The row of {@code entry} if {@link #add} made it, else null. No allocation. */
  public @Nullable Row row(Object entry) {
    Row row = rows.get(entry);
    if (row != null) {
      row.lastFrame = frame;
    }
    return row;
  }

  /**
   * Starts looking for the thumbnail of {@code file}, the row of {@code entry}, if there is room.
   */
  public void add(Object entry, Path file) {
    if (released || loading >= MAX_LOADING) {
      return;
    }
    if (rows.size() >= MAX_ROWS) {
      freeHiddenRows();
      if (rows.size() >= MAX_ROWS) {
        return;
      }
    }
    Row row = new Row(file, frame);
    rows.put(entry, row);
    load(row);
  }

  /** The thumbnail at {@code thumbnail} was just written: a row waiting for it shows it. */
  public void written(Path thumbnail) {
    for (Row row : rows.values()) {
      if (thumbnail.equals(row.thumbnail)) {
        load(row);
      }
    }
  }

  private void load(Row row) {
    row.loading = true;
    loading++;
    CompletableFuture.supplyAsync(() -> read(row.file), ApiClient.executor())
        .whenCompleteAsync((found, failure) -> finish(row, found), renderThread);
  }

  /** The file's thumbnail path and image, without the image if it has none; null if it is gone. */
  private @Nullable Found read(Path file) {
    try {
      Path thumbnail = store.locate(file).thumbnail();
      if (Files.notExists(thumbnail) || Files.size(thumbnail) > ThumbnailCache.MAX_BYTES) {
        return new Found(thumbnail, null);
      }
      return new Found(thumbnail, ThumbnailCache.decode(Files.readAllBytes(thumbnail)));
    } catch (IOException exception) {
      // Gone, or a damaged PNG: the row keeps Litematica's icon.
      return null;
    }
  }

  private void finish(Row row, @Nullable Found found) {
    loading--;
    row.loading = false;
    NativeImage image = found == null ? null : found.image();
    if (released) {
      if (image != null) {
        image.close();
      }
      return;
    }
    if (found != null) {
      row.thumbnail = found.thumbnail();
    }
    if (image != null) {
      Identifier texture = SchematicVaultClient.id(TEXTURE_PATH + nextTextureId++);
      textures.register(texture, new ThumbnailTexture(texture.toString(), image));
      freeTexture(row);
      row.texture = texture;
    }
  }

  private void freeHiddenRows() {
    Iterator<Row> iterator = rows.values().iterator();
    while (iterator.hasNext()) {
      Row row = iterator.next();
      if (row.lastFrame < frame - 1 && !row.loading) {
        freeTexture(row);
        iterator.remove();
      }
    }
  }

  private void freeTexture(Row row) {
    Identifier texture = row.texture;
    if (texture != null) {
      textures.release(texture);
      row.texture = null;
    }
  }

  /** Frees every texture; images still loading are freed when they arrive. */
  public void release() {
    released = true;
    rows.values().forEach(this::freeTexture);
    rows.clear();
  }

  /** One row of the list. */
  public static final class Row {
    private final Path file;
    private long lastFrame;
    private boolean loading;
    private @Nullable Path thumbnail;
    private @Nullable Identifier texture;

    private Row(Path file, long frame) {
      this.file = file;
      this.lastFrame = frame;
    }

    /** Its texture; null while it has none (Litematica's icon stays). */
    public @Nullable Identifier texture() {
      return texture;
    }
  }

  private record Found(Path thumbnail, @Nullable NativeImage image) {}
}
