package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.core.catalog.Catalog;
import com.einsteinmods.schematicvault.core.catalog.CatalogIndex;
import com.einsteinmods.schematicvault.core.catalog.CategoryRow;
import com.einsteinmods.schematicvault.core.catalog.CategoryTree;
import java.util.List;

/** A catalog ready to show: its search index and category rows, built off the render thread. */
public record LoadedCatalog(Catalog catalog, CatalogIndex index, List<CategoryRow> categories) {
  public LoadedCatalog {
    categories = List.copyOf(categories);
  }

  public static LoadedCatalog of(Catalog catalog) {
    return new LoadedCatalog(
        catalog,
        CatalogIndex.of(catalog.schematics(), catalog.locale()),
        CategoryTree.rows(catalog));
  }
}
