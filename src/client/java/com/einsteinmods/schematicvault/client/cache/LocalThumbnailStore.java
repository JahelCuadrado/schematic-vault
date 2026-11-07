package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.core.thumbnails.ThumbnailKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Where the thumbnails of the player's own files live: {@code cache/local-thumbnails/}, one PNG per
 * version of a file ({@link ThumbnailKey}). Nothing here creates the folder or any file: only
 * generating, which the player asks for, does. A thumbnail of a file that changed since stays there
 * unused (a few KB).
 */
public final class LocalThumbnailStore {
  private static final String FOLDER = "local-thumbnails";
  private static final String EXTENSION = ".png";

  private final Path directory;

  public LocalThumbnailStore(Path cacheDirectory) {
    this.directory = cacheDirectory.resolve(FOLDER);
  }

  /** Whether any thumbnail was ever made here. One disk check. */
  public boolean exists() {
    return Files.isDirectory(directory);
  }

  /**
   * Where {@code file}'s thumbnail goes as the file stands now. Reads the disk: off the render
   * thread.
   *
   * @throws IOException when the file is gone or unreadable
   */
  public Located locate(Path file) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
    // The real path: the browser and a scan of its folder may reach one file by two ways.
    String key =
        ThumbnailKey.of(
            file.toRealPath().toString(),
            attributes.size(),
            attributes.lastModifiedTime().toMillis());
    return new Located(directory.resolve(key + EXTENSION), attributes.size());
  }

  /**
   * A file's thumbnail path, which may not exist yet.
   *
   * @param size the file's size in bytes
   */
  public record Located(Path thumbnail, long size) {}
}
