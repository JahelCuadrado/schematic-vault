package com.einsteinmods.schematicvault.core.catalog;

import org.jspecify.annotations.Nullable;

/** What an open must download, given the cache and the manifest just fetched. */
public enum RefreshPlan {
  /** The cache is current: nothing more to fetch. */
  UP_TO_DATE,
  /** Same data, other game language: only the locale. */
  LOCALE_ONLY,
  /** No cache or a new revision: every page, the taxonomy and the locale. */
  FULL;

  /**
   * @param cached the cached catalog, or null without one
   * @param revision the manifest's revision
   * @param language the API language wanted now
   */
  public static RefreshPlan decide(@Nullable Catalog cached, String revision, String language) {
    return cached == null
        ? FULL
        : fromHeader(
            new CatalogCacheHeader(cached.revision(), cached.language()), revision, language);
  }

  /**
   * {@link #decide(Catalog, String, String)} from a cache's header alone ({@link
   * CatalogCacheHeader#read}), or null without a cache.
   */
  public static RefreshPlan fromHeader(
      @Nullable CatalogCacheHeader cached, String revision, String language) {
    if (cached == null || !cached.revision().equals(revision)) {
      return FULL;
    }
    return cached.language().equals(language) ? UP_TO_DATE : LOCALE_ONLY;
  }
}
