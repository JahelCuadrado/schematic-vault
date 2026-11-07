package com.einsteinmods.schematicvault.client.net;

import com.einsteinmods.schematicvault.core.net.LocalCopyPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A local copy of the API ({@code <game>/schematicvault/api/}, the {@code public/} folder of the
 * API's repo): every address is read from that folder on {@link ApiClient#executor} ({@link
 * LocalCopyPaths}), shards' addresses included, so trusted roots do not apply. A file the copy
 * lacks fails as not found, never from the network, so two revisions never mix.
 */
final class LocalFolderSource implements ResourceSource {
  private final Path realRoot;

  /**
   * @param realRoot the copy's folder as a real path ({@link Path#toRealPath})
   */
  LocalFolderSource(Path realRoot) {
    this.realRoot = realRoot;
  }

  Path root() {
    return realRoot;
  }

  @Override
  public CompletableFuture<byte[]> fetch(String address, List<String> trustedRoots, long maxBytes) {
    return CompletableFuture.supplyAsync(() -> read(address, maxBytes), ApiClient.executor());
  }

  private byte[] read(String address, long maxBytes) {
    try {
      Path file =
          LocalCopyPaths.resolve(realRoot, address)
              .orElseThrow(
                  () -> new ApiException(FailureKind.SERVER, "unsafe API path " + address));
      if (Files.size(file) > maxBytes) {
        throw new ApiException(FailureKind.SERVER, "local file over " + maxBytes + " bytes");
      }
      return Files.readAllBytes(file);
    } catch (NoSuchFileException exception) {
      throw ApiException.notFound("not in the local API copy: " + address);
    } catch (IOException exception) {
      throw new ApiException(FailureKind.SERVER, "cannot read local " + address, exception);
    }
  }

  @Override
  public void close() {
    // Nothing held: reads in flight finish on the shared pool.
  }
}
