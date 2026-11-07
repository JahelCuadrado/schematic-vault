package com.einsteinmods.schematicvault.core.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The revision and language of a catalog cache, read from its header only ({@link
 * CatalogCacheCodec#readHeader}: magic, format version and four strings at the start of the file),
 * so a background check does not load the whole catalog.
 */
public record CatalogCacheHeader(String revision, String language) {
  /**
   * @param apiBaseUrl the API address in use: a cache of another one has no header
   * @return empty when the file is not a cache of this format and address, or is unreadable
   */
  public static Optional<CatalogCacheHeader> read(InputStream input, String apiBaseUrl)
      throws IOException {
    try {
      CatalogCacheCodec.Header header = CatalogCacheCodec.readHeader(input);
      return apiBaseUrl.equals(header.apiBaseUrl())
          ? Optional.of(new CatalogCacheHeader(header.revision(), header.language()))
          : Optional.empty();
    } catch (CatalogFormatException exception) {
      // Another format, a cut file or not a cache at all: the next full read replaces it.
      return Optional.empty();
    }
  }
}
