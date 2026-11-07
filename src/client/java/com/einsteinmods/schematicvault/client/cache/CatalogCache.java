package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.core.catalog.Catalog;
import com.einsteinmods.schematicvault.core.catalog.CatalogCacheCodec;
import com.einsteinmods.schematicvault.core.catalog.CatalogCacheHeader;
import com.einsteinmods.schematicvault.core.catalog.CatalogFormatException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The catalog file in {@code <game>/schematicvault/cache/}: read when the vault opens, written
 * after each download of a new revision (temporary file, then an atomic move). It belongs to one
 * API address: a cache written for another address reads as no cache ({@link CatalogCacheCodec}),
 * so it is downloaded again and replaced. Call off the render thread.
 */
public final class CatalogCache {
  public static final String FILE_NAME = "catalog.bin";

  /** The JSON cache of versions before the binary one: deleted once a binary cache is written. */
  private static final String OLD_FILE_NAME = "catalog.json";

  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogCache.class);

  private final Path file;
  private final Path oldFile;
  private final String apiBaseUrl;

  public CatalogCache(Path cacheDirectory, String apiBaseUrl) {
    this.file = cacheDirectory.resolve(FILE_NAME);
    this.oldFile = cacheDirectory.resolve(OLD_FILE_NAME);
    this.apiBaseUrl = apiBaseUrl;
  }

  /** The cached catalog; empty when there is none or it is unreadable (it is then replaced). */
  public Optional<Catalog> read() {
    // The codec buffers on its own: no BufferedInputStream.
    try (InputStream input = Files.newInputStream(file)) {
      return Optional.of(CatalogCacheCodec.read(input, apiBaseUrl));
    } catch (NoSuchFileException exception) {
      return Optional.empty();
    } catch (IOException | CatalogFormatException exception) {
      LOGGER.warn("Catalog cache {} is unreadable; downloading the catalog again", file, exception);
      return Optional.empty();
    }
  }

  /**
   * The cache's revision and language, read from the start of the file only; empty without a usable
   * cache.
   */
  public Optional<CatalogCacheHeader> readHeader() {
    try (InputStream input = Files.newInputStream(file)) {
      return CatalogCacheHeader.read(input, apiBaseUrl);
    } catch (NoSuchFileException exception) {
      return Optional.empty();
    } catch (IOException exception) {
      LOGGER.warn("Catalog cache {} is unreadable: {}", file, exception.toString());
      return Optional.empty();
    }
  }

  public void write(Catalog catalog) throws IOException {
    Path temp = AtomicFiles.createTemp(file);
    try {
      try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp))) {
        CatalogCacheCodec.write(catalog, apiBaseUrl, output);
      }
      AtomicFiles.moveInto(temp, file);
      Files.deleteIfExists(oldFile);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
