package com.einsteinmods.schematicvault.core.catalog;

/**
 * A design's title and description in one language, from its record ({@code translations.<lang>})
 * or its original texts; a missing one is empty.
 */
public record DetailText(String title, String description) {}
