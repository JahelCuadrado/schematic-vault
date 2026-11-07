package com.einsteinmods.schematicvault.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemotePathsTest {
  private static final String BASE = "https://example.org/vault/";

  @Test
  void acceptsApiPaths() {
    assertTrue(RemotePaths.isSafe("v1/manifest.json"));
    assertTrue(RemotePaths.isSafe("v1/files/e7/e75be6953bfd.litematic"));
    assertTrue(RemotePaths.isSafe("v1/schematics/rdn-2162e6dc-a6dd_x.json"));
  }

  @Test
  void rejectsAnythingThatCouldLeaveTheApi() {
    assertFalse(RemotePaths.isSafe("https://evil.example/v1/x.json"));
    assertFalse(RemotePaths.isSafe("//evil.example/v1/x.json"));
    assertFalse(RemotePaths.isSafe("/v1/manifest.json"));
    assertFalse(RemotePaths.isSafe("v1/../secret.json"));
    assertFalse(RemotePaths.isSafe("v1/files/../../x"));
    assertFalse(RemotePaths.isSafe("v1/./x.json"));
    assertFalse(RemotePaths.isSafe("v1//x.json"));
    assertFalse(RemotePaths.isSafe("v1/x.json?redirect=evil"));
    assertFalse(RemotePaths.isSafe("v1/x.json#fragment"));
    assertFalse(RemotePaths.isSafe("v1/%2e%2e/x.json"));
    assertFalse(RemotePaths.isSafe("v1\\..\\x.json"));
    assertFalse(RemotePaths.isSafe("v1/x:y.json"));
    assertFalse(RemotePaths.isSafe("v2/manifest.json"));
    assertFalse(RemotePaths.isSafe("v1/"));
    assertFalse(RemotePaths.isSafe("v1/dir/"));
    assertFalse(RemotePaths.isSafe("v1/ünïcode.json"));
    assertFalse(RemotePaths.isSafe("v1/" + "a".repeat(RemotePaths.MAX_LENGTH)));
  }

  @Test
  void resolvesOnlySafePathsAgainstTheBase() {
    assertEquals(
        Optional.of(URI.create("https://example.org/vault/v1/manifest.json")),
        RemotePaths.resolve(BASE, List.of(), "v1/manifest.json"));
    assertEquals(Optional.empty(), RemotePaths.resolve(BASE, List.of(), "v1/../x"));
  }

  @Test
  void fillsTemplatesWithoutLettingTheValueAddSegments() {
    assertEquals(
        Optional.of("v1/schematics/pages/3.json"),
        RemotePaths.fill("v1/schematics/pages/{page}.json", "{page}", "3"));
    assertEquals(
        Optional.empty(), RemotePaths.fill("v1/schematics/{id}.json", "{id}", "../../evil"));
    assertEquals(Optional.empty(), RemotePaths.fill("v1/schematics/x.json", "{id}", "a"));
  }

  private static final String SHARD = "https://sov-shard-05.example.dev/";

  @Test
  void shardAddressesAreSafeOnlyWithAPlainHostAndASafePath() {
    assertTrue(RemotePaths.isSafeAddress(SHARD + "v1/schematics/abf-14645.json"));
    assertTrue(RemotePaths.isSafeAddress("v1/manifest.json"));
    assertFalse(RemotePaths.isSafeAddress("http://sov-shard-05.example.dev/v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress("https://user@evil.example/v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress("https://evil.example:8080/v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress("https://Evil.example/v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress("https:///v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress("https://.example/v1/x.json"));
    assertFalse(RemotePaths.isSafeAddress(SHARD + "v1/../x.json"));
    assertFalse(RemotePaths.isSafeAddress(SHARD + "v2/x.json"));
    assertEquals("v1/images/e6/e6.png", RemotePaths.pathOf(SHARD + "v1/images/e6/e6.png"));
    assertEquals("v1/images/e6/e6.png", RemotePaths.pathOf("v1/images/e6/e6.png"));
  }

  @Test
  void hostRootIsOnlyAPlainHttpsHost() {
    assertEquals(SHARD, RemotePaths.hostRoot(SHARD));
    assertEquals(null, RemotePaths.hostRoot("https://sov-shard-05.example.dev/path/"));
    assertEquals(null, RemotePaths.hostRoot("https://sov-shard-05.example.dev"));
    assertEquals(null, RemotePaths.hostRoot("http://sov-shard-05.example.dev/"));
    assertEquals(null, RemotePaths.hostRoot("https://a@b.dev/"));
  }

  @Test
  void absoluteAddressesReachOnlyTrustedShards() {
    List<String> trusted = List.of(SHARD);
    assertEquals(
        Optional.of(URI.create(SHARD + "v1/files/e2/e2.litematic")),
        RemotePaths.resolve(BASE, trusted, SHARD + "v1/files/e2/e2.litematic"));
    assertEquals(
        Optional.of(URI.create(BASE + "v1/manifest.json")),
        RemotePaths.resolve(BASE, trusted, "v1/manifest.json"));
    assertEquals(
        Optional.empty(), RemotePaths.resolve(BASE, trusted, "https://evil.example/v1/x.json"));
    assertEquals(
        Optional.empty(), RemotePaths.resolve(BASE, List.of(), SHARD + "v1/files/e2/e2.litematic"));
  }
}
