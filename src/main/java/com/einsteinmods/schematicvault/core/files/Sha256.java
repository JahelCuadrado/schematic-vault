package com.einsteinmods.schematicvault.core.files;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** SHA-256 checks of downloaded files against the digest the API publishes. */
public final class Sha256 {
  public static final String ALGORITHM = "SHA-256";

  private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-fA-F]{64}");

  private Sha256() {}

  /** A new digest; every Java runtime ships SHA-256. */
  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("the Java runtime has no " + ALGORITHM, exception);
    }
  }

  /** Whether {@code hex} is a SHA-256 digest: 64 hex digits, any case. */
  public static boolean isWellFormed(String hex) {
    return HEX_DIGEST.matcher(hex).matches();
  }

  /** Lowercase form of a well-formed digest. */
  public static String normalize(String hex) {
    if (!isWellFormed(hex)) {
      throw new IllegalArgumentException("not a SHA-256 digest: " + hex);
    }
    return hex.toLowerCase(Locale.ROOT);
  }

  /** Whether the computed {@code digest} equals the expected hex digest. */
  public static boolean matches(byte[] digest, String expectedHex) {
    return isWellFormed(expectedHex)
        && MessageDigest.isEqual(digest, HexFormat.of().parseHex(expectedHex));
  }
}
