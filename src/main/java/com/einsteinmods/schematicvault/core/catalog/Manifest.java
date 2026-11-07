package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The API's entry point ({@code v1/manifest.json}): read on every open; {@link #revision} changes
 * whenever the catalog data changes. Endpoint templates are checked API paths with their
 * placeholder ({@code {page}}, {@code {id}}, {@code {language}}). {@link #shardRoots} are the
 * {@code https://host/} of each shard, in shard order, empty when the API has none: records live
 * there ({@code ShardRule}), and files and images may. {@link #index} is the compact index read
 * instead of the pages when the API publishes one (null: the pages).
 */
public record Manifest(
    String revision,
    String generatedAt,
    int pageCount,
    String pageTemplate,
    String schematicTemplate,
    String taxonomyPath,
    String localeTemplate,
    List<String> languages,
    List<String> shardRoots,
    @Nullable IndexEndpoint index) {
  public Manifest {
    languages = List.copyOf(languages);
    shardRoots = List.copyOf(shardRoots);
  }
}
