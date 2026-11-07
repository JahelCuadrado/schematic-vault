package com.einsteinmods.schematicvault.client.download;

import com.einsteinmods.schematicvault.client.cache.AtomicFiles;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.ApiException;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.client.preview.EditedFile;
import com.einsteinmods.schematicvault.core.files.SafeFileNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes design files into one folder: into a temporary file there, moved (atomically) to the first
 * free name of {@code name.ext}, {@code name (2).ext}... It holds no HTTP client: the disk work
 * runs on the I/O pool ({@link ApiClient#executor}), which exists without one.
 */
public final class SchematicFileWriter {
  private static final Logger LOGGER = LoggerFactory.getLogger(SchematicFileWriter.class);

  private final Path folder;

  public SchematicFileWriter(Path folder) {
    this.folder = folder.toAbsolutePath().normalize();
  }

  public Path folder() {
    return folder;
  }

  /**
   * Saves an edited design as {@code name} (made safe, with its format's extension) under the first
   * free name, on the I/O pool; the saved path, or a {@link FailureKind#DISK} failure.
   */
  public CompletableFuture<Path> saveEdited(EditedFile edited, String name) {
    Path target = folder.resolve(SafeFileNames.fileName(name, edited.format()));
    return CompletableFuture.supplyAsync(() -> write(target, edited.bytes()), ApiClient.executor());
  }

  /** Writes {@code bytes} into a temporary file, then moves it to the first free name. */
  Path write(Path target, byte[] bytes) {
    Path temp;
    try {
      temp = AtomicFiles.createTemp(target);
    } catch (IOException exception) {
      throw new ApiException(FailureKind.DISK, "creating a file in " + folder, exception);
    }
    try {
      Files.write(temp, bytes);
      String name =
          SafeFileNames.unique(
              target.getFileName().toString(),
              candidate -> Files.exists(folder.resolve(candidate)));
      Path saved = folder.resolve(name);
      AtomicFiles.moveInto(temp, saved);
      return saved;
    } catch (IOException exception) {
      throw new ApiException(FailureKind.DISK, "saving " + target.getFileName(), exception);
    } finally {
      deleteQuietly(temp);
    }
  }

  private static void deleteQuietly(Path temp) {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException exception) {
      // A leftover has a .tmp name, which Litematica does not list.
      LOGGER.warn("Could not delete the temporary file {}", temp, exception);
    }
  }
}
