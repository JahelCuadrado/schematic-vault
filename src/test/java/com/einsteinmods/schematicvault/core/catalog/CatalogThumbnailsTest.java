package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CatalogThumbnailsTest {
  private static final String DIGEST =
      "8388db12cb2b2c3815999e32439e3629ff0e94d97453c11d1a719f03de46fbe3";
  private static final String FILE_URL = "v1/files/83/" + DIGEST + ".litematic";
  private static final String THUMBNAIL_URL = "v1/images/7a/7a2f.thumb96.png";

  private static SchematicSummary design(@Nullable String file, @Nullable String thumbnail) {
    return new SchematicSummary(
        "id",
        "title",
        List.of(),
        List.of(),
        "games",
        null,
        "source",
        null,
        new BlockSize(1, 1, 1),
        1,
        null,
        false,
        0,
        file,
        thumbnail,
        "2026-10-24T01:45:59Z",
        null,
        0,
        false,
        SchematicSummary.NO_POPULARITY,
        null,
        0,
        false);
  }

  @Test
  void aDesignIsFoundByItsPrimaryFilesDigest() {
    assertEquals(
        Map.of(DIGEST, THUMBNAIL_URL),
        CatalogThumbnails.byFileDigest(List.of(design(FILE_URL, THUMBNAIL_URL))));
  }

  /** A shard address is stored compactly: its digest is read from what is stored. */
  @Test
  void aCompactShardAddressGivesItsStoredDigest() {
    String shard = "https://sov-shard-28.example.workers.dev/";
    assertEquals(
        Map.of(DIGEST, shard + THUMBNAIL_URL),
        CatalogThumbnails.byFileDigest(List.of(design(shard + FILE_URL, shard + THUMBNAIL_URL))));
  }

  @Test
  void aDesignWithoutFileOrThumbnailIsLeftOut() {
    assertTrue(
        CatalogThumbnails.byFileDigest(List.of(design(null, THUMBNAIL_URL), design(FILE_URL, null)))
            .isEmpty());
  }

  @Test
  void aPathNotNamedByADigestMatchesNothing() {
    assertNull(CatalogThumbnails.digestOf("v1/files/83/my-farm.litematic"));
    assertEquals(
        DIGEST, CatalogThumbnails.digestOf("v1/files/83/" + DIGEST.toUpperCase() + ".nbt"));
  }
}
