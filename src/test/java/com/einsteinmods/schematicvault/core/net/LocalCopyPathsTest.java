package com.einsteinmods.schematicvault.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCopyPathsTest {
  private static final String SHARD = "https://sov-shard-07.example.workers.dev/";
  private static final String RECORD = "v1/schematics/abf-10012.json";

  @TempDir Path temp;
  private Path root;

  @BeforeEach
  void createCopy() throws IOException {
    root = Files.createDirectories(temp.resolve("api")).toRealPath();
    Files.createDirectories(root.resolve("v1/schematics"));
    Files.writeString(root.resolve(RECORD), "{}");
    Files.writeString(root.resolve("v1/manifest.json"), "{}");
  }

  @Test
  void mapsApiPathsAndShardAddressesToTheSameTree() throws IOException {
    Path record = root.resolve(RECORD);
    assertEquals(Optional.of(record), LocalCopyPaths.resolve(root, RECORD));
    assertEquals(Optional.of(record), LocalCopyPaths.resolve(root, SHARD + RECORD));
    assertEquals(
        Optional.of(root.resolve("v1/manifest.json")),
        LocalCopyPaths.resolve(root, "v1/manifest.json"));
  }

  @Test
  void rejectsUnsafeAddresses() throws IOException {
    assertTrue(LocalCopyPaths.resolve(root, "v1/../outside.json").isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, "/etc/passwd").isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, "file:///etc/passwd").isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, "http://sov-shard-07.example/" + RECORD).isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, SHARD + "v1/../x.json").isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, "v2/manifest.json").isEmpty());
  }

  @Test
  void missingFilesAndFoldersAreNotFound() {
    assertThrows(NoSuchFileException.class, () -> LocalCopyPaths.resolve(root, "v1/none.json"));
    assertThrows(NoSuchFileException.class, () -> LocalCopyPaths.resolve(root, "v1/schematics"));
  }

  @Test
  void rejectsLinksThatLeaveTheCopy() throws IOException {
    Path outside = Files.writeString(temp.resolve("secret.json"), "{}");
    Path outsideFolder = Files.createDirectories(temp.resolve("elsewhere"));
    Files.writeString(outsideFolder.resolve("x.json"), "{}");
    try {
      Files.createSymbolicLink(root.resolve("v1/link.json"), outside);
      Files.createSymbolicLink(root.resolve("v1/files"), outsideFolder);
    } catch (UnsupportedOperationException | IOException exception) {
      abort("no symbolic links on this file system: nothing can escape through one");
    }
    assertTrue(LocalCopyPaths.resolve(root, "v1/link.json").isEmpty());
    assertTrue(LocalCopyPaths.resolve(root, "v1/files/x.json").isEmpty());
  }

  @Test
  void cacheKeyNeverEqualsAnOnlineAddress() {
    String key = LocalCopyPaths.cacheKey(root);
    assertTrue(key.startsWith("file:"));
    assertFalse(key.startsWith("http"));
  }
}
