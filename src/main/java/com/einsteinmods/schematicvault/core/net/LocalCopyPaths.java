package com.einsteinmods.schematicvault.core.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where an API address lives in a local copy of the API (the {@code public/} folder of its repo):
 * the folder plus the address's API path ({@link RemotePaths#pathOf}, so a shard's address maps to
 * the same tree). Only {@link RemotePaths#isSafeAddress} addresses map, and the file found must
 * stay inside the folder after following links (real paths compared), so nothing outside it is
 * read.
 */
public final class LocalCopyPaths {
  private LocalCopyPaths() {}

  /**
   * The real path of {@code address}'s file under {@code realRoot} (a real path, see {@link
   * Path#toRealPath}); empty when the address is unsafe or its file leaves the folder.
   *
   * @throws NoSuchFileException when the copy has no such file (or it is not a regular file)
   */
  public static Optional<Path> resolve(Path realRoot, String address) throws IOException {
    if (!RemotePaths.isSafeAddress(address)) {
      return Optional.empty();
    }
    Path candidate = realRoot.resolve(RemotePaths.pathOf(address)).normalize();
    if (!candidate.startsWith(realRoot)) {
      return Optional.empty();
    }
    Path real = candidate.toRealPath();
    if (!real.startsWith(realRoot)) {
      return Optional.empty();
    }
    if (!Files.isRegularFile(real)) {
      throw new NoSuchFileException(real.toString());
    }
    return Optional.of(real);
  }

  /**
   * The catalog cache's address for a copy at {@code realRoot}: a {@code file:} URI, never equal to
   * an http(s) base URL, so a cache of the copy and one of the online API never mix.
   */
  public static String cacheKey(Path realRoot) {
    return realRoot.toUri().toString();
  }
}
