package com.einsteinmods.schematicvault.core.catalog;

import org.jspecify.annotations.Nullable;

/** A credited author of a design, as the detail record lists it. */
public record SchematicAuthor(String name, @Nullable String role) {}
