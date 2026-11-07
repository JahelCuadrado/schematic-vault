package com.einsteinmods.schematicvault.testing;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a local build of the API keeps a file (dev-only tests). Since API 2.0.0 files and images
 * live on shard hosts, built under {@code ../.deploy/<shard>/public/} beside the core's {@code
 * public/}.
 */
public final class LocalApiFiles {
  private static final String DEPLOY_FOLDER = ".deploy";
  private static final String SHARD_FOLDER_GLOB = "sov-shard-*";
  private static final String PUBLIC_FOLDER = "public";
  private static final String HTTPS = "https://";
  private static final String FILES_PATH = "v1/files/";

  /** System property with the local API build's core {@code public/} folder (dev only). */
  private static final String API_FOLDER_PROPERTY = "schematicvault.apiFolder";

  /** A folder that never exists, so tests skip where no local API build is configured. */
  private static final String NO_API_FOLDER = "no-local-api-build";

  private LocalApiFiles() {}

  /**
   * The local API build's core {@code public/} folder, from {@code -Dschematicvault.apiFolder} or
   * the {@code SCHEMATIC_VAULT_API_DIR} environment variable (see build.gradle); a missing folder
   * when neither is set, so the real-data tests skip themselves.
   */
  public static Path apiFolder() {
    String folder = System.getProperty(API_FOLDER_PROPERTY, "");
    return Path.of(folder.isBlank() ? NO_API_FOLDER : folder);
  }

  /** The local file of {@code address} (an API path or a shard's address) under {@code core}. */
  public static Path of(Path core, String address) {
    if (!address.startsWith(HTTPS)) {
      return core.resolve(address);
    }
    String host = address.substring(HTTPS.length(), address.indexOf('/', HTTPS.length()));
    String shard = host.substring(0, host.indexOf('.'));
    return core.resolveSibling(DEPLOY_FOLDER)
        .resolve(shard)
        .resolve(PUBLIC_FOLDER)
        .resolve(RemotePaths.pathOf(address));
  }

  /**
   * The local copy of {@code name} under {@code v1/files/} (such as {@code 9f/9f…e3.litematic}): in
   * the core's folder, or else in whichever shard holds it.
   */
  public static Path file(Path core, String name) throws IOException {
    Path inCore = core.resolve(FILES_PATH).resolve(name);
    if (Files.exists(inCore)) {
      return inCore;
    }
    Path deploy = core.resolveSibling(DEPLOY_FOLDER);
    if (Files.isDirectory(deploy)) {
      try (DirectoryStream<Path> shards = Files.newDirectoryStream(deploy, SHARD_FOLDER_GLOB)) {
        for (Path shard : shards) {
          Path candidate = shard.resolve(PUBLIC_FOLDER).resolve(FILES_PATH).resolve(name);
          if (Files.exists(candidate)) {
            return candidate;
          }
        }
      }
    }
    return inCore;
  }
}
