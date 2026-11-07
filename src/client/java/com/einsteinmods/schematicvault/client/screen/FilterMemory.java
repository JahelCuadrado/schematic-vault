package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.core.catalog.CatalogFilter;

/**
 * The vault's last filter (search text included) for the rest of the game session: reopening the
 * vault shows the same list, and nothing is written to disk, so a restart starts clean. A static
 * field because each opening of the vault has its own screen and {@code VaultSession}, both thrown
 * away on close. Render thread only.
 */
final class FilterMemory {
  private static CatalogFilter filter = CatalogFilter.NONE;

  private FilterMemory() {}

  static CatalogFilter get() {
    return filter;
  }

  static void set(CatalogFilter value) {
    filter = value;
  }
}
