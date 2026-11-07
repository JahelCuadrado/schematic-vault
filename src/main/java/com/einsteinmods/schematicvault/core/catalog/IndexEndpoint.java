package com.einsteinmods.schematicvault.core.catalog;

/**
 * The catalog's compact index as the manifest announces it ({@code endpoints.index} and {@code
 * index.partCount}): every summary as positional rows, split in parts {@code 1..partCount} that,
 * joined in order, list the designs in the pages' order. Read by {@link IndexPartJson}.
 *
 * @param template checked API path with its {@code {part}} placeholder
 * @param partCount parts to fetch, 1 to {@link ApiJson#MAX_INDEX_PARTS}
 */
public record IndexEndpoint(String template, int partCount) {}
