package com.einsteinmods.schematicvault.client.download;

import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.client.preview.EditedFile;
import com.einsteinmods.schematicvault.core.catalog.SchematicFile;
import com.einsteinmods.schematicvault.core.files.SafeFileNames;
import com.einsteinmods.schematicvault.core.files.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Saves design files into {@code <game>/schematics/<download folder>/}, where Litematica lists them
 * by itself. A file is streamed into a temporary file in that folder while its SHA-256 is computed;
 * only a file matching the API's digest is moved (atomically) to its safe name, the first free one
 * of {@code name.ext}, {@code name (2).ext}...; anything else is deleted. An edited design ({@link
 * #saveEdited}) is saved the same way under the name the player typed, without the digest check
 * (its content changed).
 */
public final class Downloader {
  /** No design file is near this; the API's largest is 10 MB. */
  public static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;

  private final ApiClient api;
  private final SchematicFileWriter writer;

  public Downloader(ApiClient api, Path folder) {
    this.api = api;
    this.writer = new SchematicFileWriter(folder);
  }

  public Path folder() {
    return writer.folder();
  }

  /** Where {@code file} goes under its own safe name (before any collision suffix). */
  public Path target(SchematicFile file) {
    return folder().resolve(SafeFileNames.fileName(file.fileName(), file.format()));
  }

  /**
   * The files already saved under their safe name with the size the API gives, by file id (a cheap
   * check: no hashing). Call off the render thread.
   */
  public Map<String, Path> existing(List<SchematicFile> files) {
    Map<String, Path> saved = new HashMap<>();
    for (SchematicFile file : files) {
      Path target = target(file);
      if (hasSize(target, file.sizeBytes())) {
        saved.put(file.id(), target);
      }
    }
    return saved;
  }

  /** Whether {@code path} is a file of {@code size} bytes; false when it cannot be read. */
  private static boolean hasSize(Path path, long size) {
    try {
      return Files.isRegularFile(path) && Files.size(path) == size;
    } catch (IOException exception) {
      return false;
    }
  }

  /** Downloads, checks and saves {@code file}; the saved path, or a failure with its kind. */
  public CompletableFuture<Path> download(SchematicFile file) {
    return api.fetch(file.url(), maxBytes(file))
        .thenApplyAsync(bytes -> save(file, bytes), ApiClient.executor());
  }

  /**
   * The most bytes {@code file} may take: the API's size, never past {@link #MAX_DOWNLOAD_BYTES}.
   */
  public static long maxBytes(SchematicFile file) {
    return file.sizeBytes() > 0
        ? Math.min(file.sizeBytes(), MAX_DOWNLOAD_BYTES)
        : MAX_DOWNLOAD_BYTES;
  }

  /**
   * Checks downloaded bytes against the API's digest.
   *
   * @throws ApiException with {@link FailureKind#CHECKSUM} when they differ
   */
  public static void verify(SchematicFile file, byte[] bytes) {
    if (!Sha256.matches(Sha256.newDigest().digest(bytes), file.sha256())) {
      throw new ApiException(FailureKind.CHECKSUM, "SHA-256 mismatch for " + file.url());
    }
  }

  /** Checks the bytes against the API's digest, then writes them under a free safe name. */
  private Path save(SchematicFile file, byte[] bytes) {
    verify(file, bytes);
    return writer.write(target(file), bytes);
  }

  /** {@link SchematicFileWriter#saveEdited} into this folder. */
  public CompletableFuture<Path> saveEdited(EditedFile edited, String name) {
    return writer.saveEdited(edited, name);
  }
}
