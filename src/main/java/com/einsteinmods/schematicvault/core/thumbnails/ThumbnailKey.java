package com.einsteinmods.schematicvault.core.thumbnails;

import com.einsteinmods.schematicvault.core.files.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * The name of a local file's thumbnail: a digest of its path, size and modified time. A file that
 * changes on disk gets another name, so it reads as having no thumbnail until one is made again; a
 * digest because a path may hold anything and be longer than a file name may be.
 */
public final class ThumbnailKey {
  private static final char SEPARATOR = '|';

  private ThumbnailKey() {}

  /** The key of the file at {@code path}, of {@code size} bytes, modified at {@code millis}. */
  public static String of(String path, long size, long millis) {
    String identity = path + SEPARATOR + size + SEPARATOR + millis;
    return HexFormat.of()
        .formatHex(Sha256.newDigest().digest(identity.getBytes(StandardCharsets.UTF_8)));
  }
}
