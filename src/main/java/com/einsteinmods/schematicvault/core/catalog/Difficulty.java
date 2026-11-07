package com.einsteinmods.schematicvault.core.catalog;

import java.util.Optional;

/** How hard a design is to build, as the API rates it (page item {@code difficulty}). */
public enum Difficulty {
  EASY("easy"),
  MEDIUM("medium"),
  HARD("hard");

  private final String id;

  Difficulty(String id) {
    this.id = id;
  }

  /** The API's value, also written to the cache. */
  public String id() {
    return id;
  }

  /** The difficulty with this API value; empty for a missing or unknown one. */
  public static Optional<Difficulty> fromId(String id) {
    for (Difficulty difficulty : values()) {
      if (difficulty.id.equals(id)) {
        return Optional.of(difficulty);
      }
    }
    return Optional.empty();
  }
}
