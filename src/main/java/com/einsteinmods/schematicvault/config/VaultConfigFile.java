package com.einsteinmods.schematicvault.config;

import com.einsteinmods.schematicvault.core.config.VaultSettings;
import org.jspecify.annotations.Nullable;

/**
 * On-disk shape of {@code config/schematicvault.json}, written by the settings screen and readable
 * by hand. Fields are nullable so a missing value (Gson reads it as null) gets its default; every
 * value is checked by {@link VaultSettings#sanitized}.
 *
 * <p>Bump {@link #CURRENT_SCHEMA_VERSION} when a field is renamed or changes meaning, and migrate
 * older files in {@link #toSettings()}.
 */
public record VaultConfigFile(
    int schemaVersion,
    @Nullable String apiBaseUrl,
    @Nullable String downloadFolder,
    @Nullable Integer previewBlockLimit) {
  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final String FILE_NAME = "schematicvault.json";

  public static final VaultConfigFile DEFAULTS =
      new VaultConfigFile(
          CURRENT_SCHEMA_VERSION,
          VaultSettings.DEFAULT_API_BASE_URL,
          VaultSettings.DEFAULT_DOWNLOAD_FOLDER,
          VaultSettings.DEFAULT_PREVIEW_BLOCK_LIMIT);

  /** The file for {@code settings}, at the current schema version. */
  public static VaultConfigFile of(VaultSettings settings) {
    return new VaultConfigFile(
        CURRENT_SCHEMA_VERSION,
        settings.apiBaseUrl(),
        settings.downloadFolder(),
        settings.previewBlockLimit());
  }

  public VaultSettings toSettings() {
    return VaultSettings.sanitized(apiBaseUrl, downloadFolder, previewBlockLimit);
  }
}
