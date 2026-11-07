package com.einsteinmods.schematicvault.core.catalog;

import com.einsteinmods.schematicvault.core.files.Sha256;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which API thumbnail belongs to a file's content. The API names each file by its SHA-256 ({@code
 * v1/files/83/8388...e3.litematic}), so a local file whose digest is a design's primary file holds
 * exactly that design, whatever the name it was saved with; an edited or other file matches
 * nothing.
 */
public final class CatalogThumbnails {
  private static final char PATH_SEPARATOR = '/';
  private static final char EXTENSION_DOT = '.';

  private CatalogThumbnails() {}

  /** The thumbnail path of each design with one, by the lowercase digest of its primary file. */
  public static Map<String, String> byFileDigest(List<SchematicSummary> designs) {
    Map<String, String> thumbnails = new HashMap<>();
    for (SchematicSummary design : designs) {
      String digest = digestOf(design.addresses());
      if (digest != null && design.hasThumbnail()) {
        thumbnails.put(digest, design.thumbnailPngUrl());
      }
    }
    return thumbnails;
  }

  /**
   * The lowercase digest the primary file is named by: the stored one, or read from an address kept
   * verbatim; null when there is no file or it is not named by one.
   */
  private static @Nullable String digestOf(DesignAddresses addresses) {
    String stored = addresses.primaryFileDigest();
    if (stored != null) {
      return stored;
    }
    String file = addresses.primaryFileUrl();
    return file == null ? null : digestOf(file);
  }

  /** The lowercase digest in the API path {@code fileUrl}; null when it is not named by one. */
  static @Nullable String digestOf(String fileUrl) {
    int start = fileUrl.lastIndexOf(PATH_SEPARATOR) + 1;
    int end = fileUrl.indexOf(EXTENSION_DOT, start);
    String name = end < 0 ? fileUrl.substring(start) : fileUrl.substring(start, end);
    return Sha256.isWellFormed(name) ? Sha256.normalize(name) : null;
  }
}
