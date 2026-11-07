package com.einsteinmods.schematicvault.core.net;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The only way an address from the API becomes a request: a relative path under {@code v1/} made of
 * {@code A-Z a-z 0-9 - _ .} and {@code /} only, resolved against the API base URL (already checked:
 * http or https, ending in {@code /}), or {@code https://host/} and such a path, which {@link
 * #resolve} sends only to a trusted host (the API's shards, from its manifest). So no other scheme,
 * host, port, query, escape or {@code ..} can send a request anywhere but the API.
 */
public final class RemotePaths {
  public static final String API_ROOT = "v1/";

  /** Longest path accepted; API paths are below 120 characters. */
  public static final int MAX_LENGTH = 512;

  /** A safe path, to check a host root with the same rules as an address. */
  private static final String PLACEHOLDER_PATH = API_ROOT + "x";

  private static final String SEPARATOR = "/";
  private static final String CURRENT_SEGMENT = ".";
  private static final String PARENT_SEGMENT = "..";
  private static final String HTTPS = "https://";
  private static final int MAX_HOST_LENGTH = 253;

  private RemotePaths() {}

  /** Whether {@code path} is a safe API path. */
  public static boolean isSafe(String path) {
    if (path.length() > MAX_LENGTH || !path.startsWith(API_ROOT) || path.endsWith(SEPARATOR)) {
      return false;
    }
    for (int index = 0; index < path.length(); index++) {
      if (!isAllowed(path.charAt(index))) {
        return false;
      }
    }
    for (String segment : path.split(SEPARATOR, -1)) {
      if (segment.isEmpty() || segment.equals(CURRENT_SEGMENT) || segment.equals(PARENT_SEGMENT)) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code address} is a safe API path, alone or after {@code https://host/}. */
  public static boolean isSafeAddress(String address) {
    return address.startsWith(HTTPS) ? hostRootOf(address) != null : isSafe(address);
  }

  /** {@code address} as given when {@link #isSafeAddress}, else empty. */
  public static Optional<String> checkedAddress(String address) {
    return isSafeAddress(address) ? Optional.of(address) : Optional.empty();
  }

  /** The API path of a safe {@code address}: itself, or what follows its host. */
  public static String pathOf(String address) {
    String root = address.startsWith(HTTPS) ? hostRootOf(address) : null;
    return root == null ? address : address.substring(root.length());
  }

  /**
   * {@code url} when it is exactly {@code https://host/} with a plain host name (lowercase letters,
   * digits, {@code -} and {@code .}; no port, user or path), else null.
   */
  public static @Nullable String hostRoot(String url) {
    return url.startsWith(HTTPS) && url.equals(hostRootOf(url + PLACEHOLDER_PATH)) ? url : null;
  }

  /** {@code path} as given when safe, else empty. */
  public static Optional<String> checked(String path) {
    return isSafe(path) ? Optional.of(path) : Optional.empty();
  }

  /**
   * The request for {@code address}: a safe path under {@code baseUrl}, or a safe absolute address
   * whose {@code https://host/} is one of {@code trustedRoots}; empty for anything else.
   */
  public static Optional<URI> resolve(
      String baseUrl, Collection<String> trustedRoots, String address) {
    if (address.startsWith(HTTPS)) {
      String root = hostRootOf(address);
      return root != null && trustedRoots.contains(root)
          ? Optional.of(URI.create(address))
          : Optional.empty();
    }
    return isSafe(address) ? Optional.of(URI.create(baseUrl + address)) : Optional.empty();
  }

  /** The {@code https://host/} of {@code address} when the rest is a safe path, else null. */
  private static @Nullable String hostRootOf(String address) {
    int slash = address.indexOf(SEPARATOR, HTTPS.length());
    if (slash <= HTTPS.length() || slash - HTTPS.length() > MAX_HOST_LENGTH) {
      return null;
    }
    for (int index = HTTPS.length(); index < slash; index++) {
      if (!isHostCharacter(address.charAt(index))) {
        return null;
      }
    }
    char first = address.charAt(HTTPS.length());
    char last = address.charAt(slash - 1);
    if (first == '.' || first == '-' || last == '.' || last == '-') {
      return null;
    }
    return isSafe(address.substring(slash + 1)) ? address.substring(0, slash + 1) : null;
  }

  private static boolean isHostCharacter(char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '.';
  }

  /**
   * {@code template} with its {@code placeholder} replaced by {@code value}, checked: empty when
   * the result is unsafe (a value with a slash or dots cannot reach another path).
   */
  public static Optional<String> fill(String template, String placeholder, String value) {
    if (!template.contains(placeholder) || value.contains(SEPARATOR)) {
      return Optional.empty();
    }
    return checked(template.replace(placeholder, value));
  }

  private static boolean isAllowed(char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= 'A' && character <= 'Z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '_'
        || character == '.'
        || character == '/';
  }
}
