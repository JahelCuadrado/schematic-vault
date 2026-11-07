package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class Sha256Test {
  /** SHA-256 of "abc" (FIPS 180-2 test vector). */
  private static final String ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  void matchesTheExpectedDigestInAnyCase() {
    byte[] digest = Sha256.newDigest().digest("abc".getBytes(StandardCharsets.US_ASCII));
    assertTrue(Sha256.matches(digest, ABC));
    assertTrue(Sha256.matches(digest, ABC.toUpperCase(Locale.ROOT)));
  }

  @Test
  void anyOtherContentOrABrokenDigestDoesNotMatch() {
    byte[] digest = Sha256.newDigest().digest("abd".getBytes(StandardCharsets.US_ASCII));
    assertFalse(Sha256.matches(digest, ABC));
    assertFalse(Sha256.matches(digest, "not a digest"));
    assertFalse(Sha256.matches(digest, ABC.substring(1)));
  }
}
