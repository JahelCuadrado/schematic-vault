package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One design as listed in the catalog pages or the compact index: what the list, the search and the
 * detail panel need before its own record is fetched. API paths are already checked ({@code
 * RemotePaths}): an unsafe one is read as null.
 *
 * @param category taxonomy path, e.g. {@code farms/mob/iron}
 * @param license SPDX id, or null when the source states no license
 * @param sourceId the source's id from the pages; empty from the index, which lacks it
 * @param minecraftVersion release name from the pages, or null (always from the index, which lacks
 *     it: the record's primary file gives it, {@link SchematicFile#minecraftVersion})
 * @param updatedAt ISO-8601 UTC instant ({@code 2026-10-24T01:45:59Z}): sorts as text
 * @param addresses its primary file and 96 px PNG thumbnail, stored compactly: read them with
 *     {@link #primaryFileUrl} and {@link #thumbnailPngUrl}
 * @param difficulty how hard it is to build, or null when the API does not rate it
 * @param verified whether the API marks it as verified
 * @param width farm width in blocks ({@link WidthBucket}); 0 when the API does not give it
 * @param status review state, or null when the API gives none or an unknown one
 * @param dataVersion newest Minecraft data version of its files ({@code dataVersionMax}); 0 when
 *     unknown
 * @param described whether the page gives it a non-blank description ({@code summary})
 * @param popularity 0-100 when the API rates it (optional field), else {@link #NO_POPULARITY}
 * @param sourceStatus state in its source (optional field), or null when absent or unknown
 * @param endorsementCount endorsements it has (optional field); 0 when absent
 * @param hasRates whether its throughput was stated (optional field); false when absent
 */
public record SchematicSummary(
    String id,
    String title,
    List<String> authors,
    List<String> tags,
    String category,
    @Nullable String license,
    String sourceId,
    @Nullable String minecraftVersion,
    BlockSize size,
    long blockCount,
    @Nullable Difficulty difficulty,
    boolean verified,
    int width,
    DesignAddresses addresses,
    String updatedAt,
    @Nullable ReviewStatus status,
    int dataVersion,
    boolean described,
    int popularity,
    @Nullable SourceStatus sourceStatus,
    int endorsementCount,
    boolean hasRates) {
  /** {@link #popularity} of a design the API does not rate. */
  public static final int NO_POPULARITY = -1;

  /** Highest {@link #popularity} the API gives. */
  public static final int MAX_POPULARITY = 100;

  /**
   * @param primaryFileUrl the primary file's API address, or null when the API gives none
   * @param thumbnailPngUrl the 96 px PNG thumbnail's API address, or null when the API has none
   */
  public SchematicSummary(
      String id,
      String title,
      List<String> authors,
      List<String> tags,
      String category,
      @Nullable String license,
      String sourceId,
      @Nullable String minecraftVersion,
      BlockSize size,
      long blockCount,
      @Nullable Difficulty difficulty,
      boolean verified,
      int width,
      @Nullable String primaryFileUrl,
      @Nullable String thumbnailPngUrl,
      String updatedAt,
      @Nullable ReviewStatus status,
      int dataVersion,
      boolean described,
      int popularity,
      @Nullable SourceStatus sourceStatus,
      int endorsementCount,
      boolean hasRates) {
    this(
        id,
        title,
        authors,
        tags,
        category,
        license,
        sourceId,
        minecraftVersion,
        size,
        blockCount,
        difficulty,
        verified,
        width,
        DesignAddresses.of(primaryFileUrl, thumbnailPngUrl),
        updatedAt,
        status,
        dataVersion,
        described,
        popularity,
        sourceStatus,
        endorsementCount,
        hasRates);
  }

  public SchematicSummary {
    authors = List.copyOf(authors);
    tags = List.copyOf(tags);
    dataVersion = Math.max(0, dataVersion);
    endorsementCount = Math.max(0, endorsementCount);
    // Out of range reads as unrated or as the top: the score never sees another value.
    popularity = popularity < 0 ? NO_POPULARITY : Math.min(popularity, MAX_POPULARITY);
  }

  /** The primary file's API address, or null; built on each call (keep it in per-frame code). */
  public @Nullable String primaryFileUrl() {
    return addresses.primaryFileUrl();
  }

  /** The 96 px PNG thumbnail's API address, or null; built on each call (keep it per frame). */
  public @Nullable String thumbnailPngUrl() {
    return addresses.thumbnailPngUrl();
  }

  /** Whether the API has a thumbnail, without building its address. */
  public boolean hasThumbnail() {
    return addresses.hasThumbnail();
  }
}
