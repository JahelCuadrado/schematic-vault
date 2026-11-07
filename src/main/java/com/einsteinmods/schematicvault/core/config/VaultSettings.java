package com.einsteinmods.schematicvault.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The settings in use, every value checked: a missing or invalid value in the file gets its default
 * ({@link #sanitized}).
 *
 * @param apiBaseUrl http(s) URL of the API root, ending in {@code /}
 * @param downloadFolder one folder name inside {@code schematics/}
 * @param previewBlockLimit designs with more blocks get no 3D preview
 */
public record VaultSettings(String apiBaseUrl, String downloadFolder, int previewBlockLimit) {
  public static final String DEFAULT_API_BASE_URL =
      "https://sov-core.opendoors-cloudflare-october996.workers.dev/";
  public static final String DEFAULT_DOWNLOAD_FOLDER = "Schematic Vault";
  public static final int DEFAULT_PREVIEW_BLOCK_LIMIT = 500_000;
  public static final int MAX_PREVIEW_BLOCK_LIMIT = 10_000_000;

  /** Smallest 3D limit: designs this small cost a few MB of mesh at most. */
  public static final int MIN_PREVIEW_BLOCK_LIMIT = 50_000;

  /** The settings screen's slider moves the 3D limit in steps of this many blocks. */
  public static final int PREVIEW_BLOCK_LIMIT_STEP = 50_000;

  /** Longest folder name accepted, in characters. */
  public static final int MAX_FOLDER_NAME_LENGTH = 64;

  public static final VaultSettings DEFAULTS =
      new VaultSettings(DEFAULT_API_BASE_URL, DEFAULT_DOWNLOAD_FOLDER, DEFAULT_PREVIEW_BLOCK_LIMIT);

  private static final String HTTP = "http";
  private static final String HTTPS = "https";
  private static final String SEPARATOR = "/";
  private static final String FOLDER_PUNCTUATION = "-_.() ";

  /** Settings from the file's values; each invalid or missing one gets its default. */
  public static VaultSettings sanitized(
      @Nullable String apiBaseUrl,
      @Nullable String downloadFolder,
      @Nullable Integer previewBlockLimit) {
    return new VaultSettings(
        apiBaseUrl == null
            ? DEFAULT_API_BASE_URL
            : baseUrl(apiBaseUrl).orElse(DEFAULT_API_BASE_URL),
        downloadFolder == null
            ? DEFAULT_DOWNLOAD_FOLDER
            : folderName(downloadFolder).orElse(DEFAULT_DOWNLOAD_FOLDER),
        previewBlockLimit == null || previewBlockLimit <= 0
            ? DEFAULT_PREVIEW_BLOCK_LIMIT
            : previewBlockLimit(previewBlockLimit));
  }

  /**
   * {@code value} kept within {@link #MIN_PREVIEW_BLOCK_LIMIT} and {@link
   * #MAX_PREVIEW_BLOCK_LIMIT}.
   */
  public static int previewBlockLimit(int value) {
    return Math.clamp(value, MIN_PREVIEW_BLOCK_LIMIT, MAX_PREVIEW_BLOCK_LIMIT);
  }

  /**
   * {@code value} on the slider's steps: the nearest multiple of {@link #PREVIEW_BLOCK_LIMIT_STEP}
   * within the limits.
   */
  public static int steppedPreviewBlockLimit(int value) {
    int bounded = previewBlockLimit(value);
    int steps = (bounded + PREVIEW_BLOCK_LIMIT_STEP / 2) / PREVIEW_BLOCK_LIMIT_STEP;
    return previewBlockLimit(steps * PREVIEW_BLOCK_LIMIT_STEP);
  }

  /**
   * {@code value} as an API base URL: http or https with a host, no user, query or fragment, with
   * exactly one trailing slash; empty when it is not one.
   */
  public static Optional<String> baseUrl(String value) {
    URI uri;
    try {
      uri = new URI(value.strip());
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase(HTTP) || scheme.equalsIgnoreCase(HTTPS))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      return Optional.empty();
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith(SEPARATOR)) {
      path = path.substring(0, path.length() - 1);
    }
    String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
    return Optional.of(
        scheme.toLowerCase(Locale.ROOT) + "://" + uri.getHost() + port + path + SEPARATOR);
  }

  /**
   * {@code value} as the downloads folder name: one name (no separator, drive or {@code ..}) of
   * letters, digits and {@code -_.() }, trimmed; empty when it is not one.
   */
  public static Optional<String> folderName(String value) {
    String name = value.strip();
    if (name.isEmpty()
        || name.length() > MAX_FOLDER_NAME_LENGTH
        || name.startsWith(".")
        || name.endsWith(".")) {
      return Optional.empty();
    }
    for (int offset = 0; offset < name.length(); ) {
      int codePoint = name.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (!Character.isLetterOrDigit(codePoint) && FOLDER_PUNCTUATION.indexOf(codePoint) < 0) {
        return Optional.empty();
      }
    }
    return Optional.of(name);
  }
}
