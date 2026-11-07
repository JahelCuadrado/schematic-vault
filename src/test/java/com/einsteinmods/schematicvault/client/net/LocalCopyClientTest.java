package com.einsteinmods.schematicvault.client.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCopyClientTest {
  private static final String ONLINE = "https://example.org/";
  private static final String RECORD = "v1/schematics/abf-1.json";
  private static final String SHARD_RECORD = "https://sov-shard-03.example.dev/" + RECORD;
  private static final byte[] BODY = "{\"id\":\"abf-1\"}".getBytes(StandardCharsets.UTF_8);
  private static final long MAX_BYTES = 1024;

  @TempDir Path temp;
  private ApiClient client;

  @BeforeEach
  void createCopy() throws IOException {
    Path root = temp.toRealPath();
    Files.createDirectories(root.resolve("v1/schematics"));
    Files.write(root.resolve(RECORD), BODY);
    client = ApiClient.localCopy(root);
  }

  @Test
  void readsPathsAndShardAddressesWithoutTrustedShards() {
    assertArrayEquals(BODY, client.fetch(RECORD, MAX_BYTES).join());
    assertArrayEquals(BODY, client.fetch(SHARD_RECORD, MAX_BYTES).join());
  }

  @Test
  void aMissingFileIsNotFound() {
    CompletionException failure =
        assertThrows(
            CompletionException.class, () -> client.fetch("v1/none.json", MAX_BYTES).join());
    assertTrue(ApiException.isNotFound(failure));
  }

  @Test
  void refusesUnsafePathsAndOversizedFiles() {
    CompletionException unsafe =
        assertThrows(CompletionException.class, () -> client.fetch("v1/../x", MAX_BYTES).join());
    assertEquals(FailureKind.SERVER, ApiException.kindOf(unsafe));
    assertFalse(ApiException.isNotFound(unsafe));
    assertThrows(CompletionException.class, () -> client.fetch(RECORD, BODY.length - 1).join());
  }

  @Test
  void hasItsOwnCacheKey() {
    assertTrue(client.isLocalCopy());
    assertTrue(client.cacheKey(ONLINE).startsWith("file:"));
    ApiClient online = new ApiClient(ONLINE, "test");
    assertFalse(online.isLocalCopy());
    assertEquals(ONLINE, online.cacheKey(ONLINE));
    online.close();
  }
}
