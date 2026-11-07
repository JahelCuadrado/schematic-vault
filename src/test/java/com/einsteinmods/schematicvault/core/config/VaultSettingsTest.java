package com.einsteinmods.schematicvault.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class VaultSettingsTest {
  @Test
  void missingValuesGetDefaults() {
    assertEquals(VaultSettings.DEFAULTS, VaultSettings.sanitized(null, null, null));
  }

  @Test
  void baseUrlIsHttpOrHttpsWithOneTrailingSlash() {
    assertEquals(
        Optional.of("http://localhost:8000/"), VaultSettings.baseUrl("http://localhost:8000"));
    assertEquals(
        Optional.of("https://jahelcuadrado.github.io/SchematicOpenVault/"),
        VaultSettings.baseUrl(" HTTPS://jahelcuadrado.github.io/SchematicOpenVault// "));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("ftp://example.org/"));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("file:///etc/"));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("example.org"));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("https://user:pw@example.org/"));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("https://example.org/?x=1"));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("https://example.org/#x"));
    assertEquals(
        VaultSettings.DEFAULT_API_BASE_URL,
        VaultSettings.sanitized("javascript:alert(1)", null, null).apiBaseUrl());
  }

  @Test
  void folderIsOneSafeName() {
    assertEquals(Optional.of("My Builds"), VaultSettings.folderName("  My Builds "));
    assertEquals(Optional.empty(), VaultSettings.folderName("../outside"));
    assertEquals(Optional.empty(), VaultSettings.folderName("a/b"));
    assertEquals(Optional.empty(), VaultSettings.folderName("a\\b"));
    assertEquals(Optional.empty(), VaultSettings.folderName("C:"));
    assertEquals(Optional.empty(), VaultSettings.folderName(".."));
    assertEquals(Optional.empty(), VaultSettings.folderName(""));
    assertEquals(
        VaultSettings.DEFAULT_DOWNLOAD_FOLDER,
        VaultSettings.sanitized(null, "../../.minecraft", null).downloadFolder());
  }

  @Test
  void previewLimitIsPositiveAndWithinBounds() {
    assertEquals(
        VaultSettings.DEFAULT_PREVIEW_BLOCK_LIMIT,
        VaultSettings.sanitized(null, null, 0).previewBlockLimit());
    assertEquals(
        VaultSettings.MIN_PREVIEW_BLOCK_LIMIT,
        VaultSettings.sanitized(null, null, 1000).previewBlockLimit());
    assertEquals(123_456, VaultSettings.sanitized(null, null, 123_456).previewBlockLimit());
    assertEquals(
        VaultSettings.MAX_PREVIEW_BLOCK_LIMIT,
        VaultSettings.sanitized(null, null, Integer.MAX_VALUE).previewBlockLimit());
  }

  @Test
  void sliderStepsStayOnMultiplesWithinBounds() {
    assertEquals(150_000, VaultSettings.steppedPreviewBlockLimit(140_000));
    assertEquals(100_000, VaultSettings.steppedPreviewBlockLimit(124_999));
    assertEquals(VaultSettings.MIN_PREVIEW_BLOCK_LIMIT, VaultSettings.steppedPreviewBlockLimit(-5));
    assertEquals(
        VaultSettings.MAX_PREVIEW_BLOCK_LIMIT,
        VaultSettings.steppedPreviewBlockLimit(Integer.MAX_VALUE));
    assertEquals(
        VaultSettings.DEFAULT_PREVIEW_BLOCK_LIMIT,
        VaultSettings.steppedPreviewBlockLimit(VaultSettings.DEFAULT_PREVIEW_BLOCK_LIMIT));
  }

  @Test
  void textFieldsAreAcceptedOnlyWhenValid() {
    assertEquals(Optional.empty(), VaultSettings.baseUrl(""));
    assertEquals(Optional.empty(), VaultSettings.baseUrl("https://"));
    assertEquals(
        Optional.of("https://example.org/api/"), VaultSettings.baseUrl("https://example.org/api"));
    assertEquals(Optional.empty(), VaultSettings.folderName("."));
    assertEquals(
        Optional.empty(),
        VaultSettings.folderName("x".repeat(VaultSettings.MAX_FOLDER_NAME_LENGTH + 1)));
    assertEquals(Optional.of("Farms (2026)"), VaultSettings.folderName("Farms (2026)"));
  }
}
