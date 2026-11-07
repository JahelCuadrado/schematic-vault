package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;

/** Small designs for the catalog tests. */
final class Samples {
  /** The API address the sample caches belong to. */
  static final String API = "https://example.org/api/";

  private Samples() {}

  static SchematicSummary schematic(
      String id, String title, String category, long blocks, String updatedAt, String... tags) {
    return new SchematicSummary(
        id,
        title,
        List.of("Señor Builder"),
        List.of(tags),
        category,
        null,
        "source",
        "1.21.1",
        new BlockSize(3, 4, 5),
        blocks,
        Difficulty.MEDIUM,
        false,
        3,
        "v1/files/aa/" + id + ".litematic",
        null,
        updatedAt,
        ReviewStatus.UNREVIEWED,
        0,
        false,
        SchematicSummary.NO_POPULARITY,
        null,
        0,
        false);
  }
}
