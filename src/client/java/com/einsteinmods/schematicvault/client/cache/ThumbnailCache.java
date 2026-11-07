package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thumbnails for the rows on screen. A thumbnail's URL never changes content (it names the image's
 * hash), so the PNG is downloaded once, ever, into {@code cache/thumbnails/} and read from there
 * afterwards. Textures are created only when a row (or the detail panel) asks for one, at most
 * {@link #MAX_LOADING} at a time; the {@link #MAX_TEXTURES} last used stay on the GPU (about 37 KB
 * each), older ones are freed, and {@link #release} frees all when the vault closes. A thumbnail
 * that fails is not asked for again while the vault is open.
 *
 * <p>Render thread only, apart from the file and network work it hands to the I/O pool. {@link
 * #texture} allocates nothing once a thumbnail is loaded or failed (a map lookup).
 */
public final class ThumbnailCache {
  /**
   * More than the rows a 4K window shows at GUI scale 1, so visible rows never evict each other.
   */
  public static final int MAX_TEXTURES = 160;

  private static final int MAX_LOADING = 4;
  static final int MAX_BYTES = 512 * 1024;

  /** The API makes 96 px thumbnails; anything much larger is not one. */
  private static final int MAX_SIDE = 256;

  private static final String TEXTURE_PATH = "thumbnail/";
  private static final char PATH_SEPARATOR = '/';
  private static final char FILE_SEPARATOR = '_';

  private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailCache.class);

  private final ApiClient api;
  private final Path directory;
  private final TextureManager textures;
  private final Executor renderThread;

  /** Loaded textures by URL, least recently drawn first. */
  private final LinkedHashMap<String, Thumbnail> loaded = new LinkedHashMap<>(16, 0.75F, true);

  private final Set<String> loading = new HashSet<>();
  private final Set<String> failed = new HashSet<>();
  private int nextTextureId;
  private boolean released;

  public ThumbnailCache(
      ApiClient api, Path directory, TextureManager textures, Executor renderThread) {
    this.api = api;
    this.directory = directory;
    this.textures = textures;
    this.renderThread = renderThread;
  }

  /**
   * The texture of the thumbnail at {@code url} if it is loaded, else null (draw a placeholder):
   * starts loading it when there is room, so asking every frame for the visible rows is enough.
   */
  public @Nullable Thumbnail texture(String url) {
    Thumbnail texture = loaded.get(url);
    if (texture != null
        || released
        || loading.size() >= MAX_LOADING
        || failed.contains(url)
        || loading.contains(url)) {
      return texture;
    }
    loading.add(url);
    CompletableFuture.supplyAsync(() -> readImage(url), ApiClient.executor())
        .thenCompose(image -> image != null ? CompletableFuture.completedFuture(image) : fetch(url))
        .whenCompleteAsync((image, failure) -> finish(url, image, failure), renderThread);
    return null;
  }

  /** Frees every texture; images still loading are freed when they arrive. */
  public void release() {
    released = true;
    for (Thumbnail thumbnail : loaded.values()) {
      textures.release(thumbnail.texture());
    }
    loaded.clear();
  }

  /** The cached file's image, or null when it is not cached yet (or unreadable: fetched again). */
  private @Nullable NativeImage readImage(String url) {
    Path file = file(url);
    if (Files.notExists(file)) {
      return null;
    }
    try {
      return decode(Files.readAllBytes(file));
    } catch (IOException exception) {
      LOGGER.warn("Unreadable cached thumbnail {}; downloading it again", file);
      return null;
    }
  }

  private CompletableFuture<NativeImage> fetch(String url) {
    return api.fetch(url, MAX_BYTES)
        .thenApply(
            bytes -> {
              try {
                NativeImage image = decode(bytes);
                if (!api.isLocalCopy()) {
                  // A local copy of the API already keeps the PNG on disk.
                  AtomicFiles.write(file(url), bytes);
                }
                return image;
              } catch (IOException exception) {
                throw new IllegalStateException("bad thumbnail " + url, exception);
              }
            });
  }

  static NativeImage decode(byte[] bytes) throws IOException {
    NativeImage image = NativeImage.read(bytes);
    if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
      image.close();
      throw new IOException("thumbnail larger than " + MAX_SIDE + " px");
    }
    return image;
  }

  /** On the render thread: uploads the image, or remembers the failure. */
  private void finish(String url, @Nullable NativeImage image, @Nullable Throwable failure) {
    loading.remove(url);
    if (image == null || failure != null) {
      // Offline or a broken image: the row keeps its placeholder until the vault opens again.
      failed.add(url);
      return;
    }
    if (released) {
      image.close();
      return;
    }
    Identifier texture = SchematicVaultClient.id(TEXTURE_PATH + nextTextureId++);
    int width = image.getWidth();
    int height = image.getHeight();
    textures.register(texture, new ThumbnailTexture(texture.toString(), image));
    loaded.put(url, new Thumbnail(texture, width, height));
    evictOldest();
  }

  private void evictOldest() {
    Iterator<Map.Entry<String, Thumbnail>> oldest = loaded.entrySet().iterator();
    while (loaded.size() > MAX_TEXTURES && oldest.hasNext()) {
      textures.release(oldest.next().getValue().texture());
      oldest.remove();
    }
  }

  /**
   * The cache file of a thumbnail: its API path (only safe characters, without a shard's host, so
   * the name holds no {@code :}) flattened to one name.
   */
  private Path file(String url) {
    return fileIn(directory, url);
  }

  /** Where the thumbnail at {@code url} is cached under {@code directory}, if it is. */
  public static Path fileIn(Path directory, String url) {
    return directory.resolve(RemotePaths.pathOf(url).replace(PATH_SEPARATOR, FILE_SEPARATOR));
  }

  /** A loaded thumbnail: its texture and size in pixels. */
  public record Thumbnail(Identifier texture, int width, int height) {}
}
