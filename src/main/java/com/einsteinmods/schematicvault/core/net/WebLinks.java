package com.einsteinmods.schematicvault.core.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/** Links to web pages from the API (a design's source page): only http(s) with a host. */
public final class WebLinks {
  private static final String HTTP = "http";
  private static final String HTTPS = "https";

  private WebLinks() {}

  /** {@code url} as a web page address, or empty when it is anything else. */
  public static Optional<URI> parse(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    if (scheme == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
      return Optional.empty();
    }
    String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    return lowerScheme.equals(HTTP) || lowerScheme.equals(HTTPS)
        ? Optional.of(uri)
        : Optional.empty();
  }
}
