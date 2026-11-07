package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;

/**
 * An edited design file ready to save.
 *
 * @param bytes the whole file, gzip-compressed
 * @param format its format (a converted MCEdit file is a litematic)
 */
public record EditedFile(byte[] bytes, SchematicFormat format) {}
