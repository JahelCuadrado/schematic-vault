package com.einsteinmods.schematicvault.client.net;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Where {@link ApiClient} reads the API from: the network or a local copy of the API. */
sealed interface ResourceSource permits HttpSource, LocalFolderSource {
  /**
   * The body of {@code address} (an API path or a shard's address), at most {@code maxBytes}; fails
   * with an {@link ApiException}. No thread waits for the network.
   *
   * @param trustedRoots the shards' {@code https://host/} a request may reach
   */
  CompletableFuture<byte[]> fetch(String address, List<String> trustedRoots, long maxBytes);

  /** Lets requests in flight finish, then frees what the source holds. */
  void close();
}
