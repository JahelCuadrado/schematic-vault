package com.einsteinmods.schematicvault.core.catalog;

/** A design's bounding box in blocks; 0 for an axis the API does not give. */
public record BlockSize(int x, int y, int z) {
  public static final BlockSize UNKNOWN = new BlockSize(0, 0, 0);

  public boolean isKnown() {
    return x > 0 && y > 0 && z > 0;
  }
}
