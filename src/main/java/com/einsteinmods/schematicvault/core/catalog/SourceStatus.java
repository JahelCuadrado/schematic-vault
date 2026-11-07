package com.einsteinmods.schematicvault.core.catalog;

import java.util.Optional;

/**
 * A design's state in its source community, in that source's own words (page item {@code
 * sourceStatus}, optional): only what {@link DesignScore} needs to tell apart, by the API's values.
 */
public enum SourceStatus {
  CURATED("curated"),
  ACCEPTED("accepted"),
  SUBMITTED("submitted"),
  REJECTED("rejected"),
  DEPRECATED("deprecated"),
  LEGACY("legacy"),
  EXPERIMENTAL("experimental"),
  COMMUNITY("community"),
  CHALLENGE("challenge");

  private final String id;

  SourceStatus(String id) {
    this.id = id;
  }

  /** The API's value, also written to the cache. */
  public String id() {
    return id;
  }

  /** The status with this API value; empty for a missing or unknown one. */
  public static Optional<SourceStatus> fromId(String id) {
    for (SourceStatus status : values()) {
      if (status.id.equals(id)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }
}
