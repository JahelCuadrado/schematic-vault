package com.einsteinmods.schematicvault.core.catalog;

import java.util.Optional;

/**
 * Where a design stands in its source's review, as the API states it (page item {@code status}).
 */
public enum ReviewStatus {
  /** Approved by the source's curators. */
  REVIEWED("reviewed"),
  /** Shared without review. */
  UNREVIEWED("unreviewed"),
  /** Its reviewers did not approve it (it may still work). */
  REJECTED("rejected"),
  /** Obsolete, broken or superseded. */
  OUTDATED("outdated");

  private final String id;

  ReviewStatus(String id) {
    this.id = id;
  }

  /** The API's value, also written to the cache. */
  public String id() {
    return id;
  }

  /** The status with this API value; empty for a missing or unknown one. */
  public static Optional<ReviewStatus> fromId(String id) {
    for (ReviewStatus status : values()) {
      if (status.id.equals(id)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }
}
