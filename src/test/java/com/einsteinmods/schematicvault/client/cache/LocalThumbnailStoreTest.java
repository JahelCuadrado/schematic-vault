package com.einsteinmods.schematicvault.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalThumbnailStoreTest {
  private static final FileTime FIRST_TIME = FileTime.fromMillis(1_000_000);
  private static final FileTime LATER_TIME = FileTime.fromMillis(2_000_000);

  @TempDir Path folder;

  private Path schematic() throws IOException {
    Path file = Files.writeString(folder.resolve("farm.litematic"), "blocks");
    Files.setLastModifiedTime(file, FIRST_TIME);
    return file;
  }

  @Test
  void lookingForAThumbnailCreatesNothing() throws IOException {
    Path cache = folder.resolve("cache");
    LocalThumbnailStore store = new LocalThumbnailStore(cache);
    assertFalse(store.exists());
    store.locate(schematic());
    assertFalse(store.exists());
    assertFalse(Files.exists(cache));
  }

  @Test
  void aChangedFileHasAnotherThumbnail() throws IOException {
    LocalThumbnailStore store = new LocalThumbnailStore(folder.resolve("cache"));
    Path file = schematic();
    LocalThumbnailStore.Located before = store.locate(file);
    assertEquals(before, store.locate(file));
    assertEquals(Files.size(file), before.size());

    Files.setLastModifiedTime(file, LATER_TIME);
    assertNotEquals(before.thumbnail(), store.locate(file).thumbnail());
  }

  @Test
  void aThumbnailIsAPngInTheFolder() throws IOException {
    Path cache = folder.resolve("cache");
    Path thumbnail = new LocalThumbnailStore(cache).locate(schematic()).thumbnail();
    assertEquals(cache.resolve("local-thumbnails"), thumbnail.getParent());
    assertTrue(thumbnail.getFileName().toString().endsWith(".png"));
  }

  @Test
  void aMissingFileIsAnError() {
    LocalThumbnailStore store = new LocalThumbnailStore(folder.resolve("cache"));
    assertThrows(NoSuchFileException.class, () -> store.locate(folder.resolve("gone.nbt")));
  }
}
