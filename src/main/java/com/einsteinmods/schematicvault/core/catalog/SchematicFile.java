package com.einsteinmods.schematicvault.core.catalog;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import org.jspecify.annotations.Nullable;

/**
 * One downloadable file of a design.
 *
 * @param url checked API path ({@code v1/files/...})
 * @param sha256 lowercase hex digest the downloaded bytes must match
 * @param minecraftVersion release name of its data version, or null when the API does not know it
 */
public record SchematicFile(
    String id,
    String fileName,
    SchematicFormat format,
    long sizeBytes,
    String sha256,
    String url,
    @Nullable String minecraftVersion) {}
