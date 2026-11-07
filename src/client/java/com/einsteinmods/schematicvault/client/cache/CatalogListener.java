package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.client.net.FailureKind;
import org.jspecify.annotations.Nullable;

/** What {@link CatalogRepository} reports, always on the render thread, in this order. */
public interface CatalogListener {
  /** First: the cached catalog, or null without a usable cache. */
  void onCacheRead(@Nullable LoadedCatalog cached);

  /** While the designs download: {@code done} of {@code total} pages, or index parts. */
  void onProgress(int done, int total);

  /** Last, on success: the cache was current. */
  void onCurrent();

  /** Last, on success: a new catalog (new revision or language), already cached. */
  void onUpdated(LoadedCatalog catalog);

  /** Last, on failure: the cache (if any) stays in use. */
  void onFailed(FailureKind kind);
}
