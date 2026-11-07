package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class DesignAddressesTest {
  private static final String FILE_DIGEST =
      "e2310b5e8f3a4c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9012345678d4";
  private static final String THUMBNAIL_DIGEST =
      "e6a0ffeeddccbbaa99887766554433221100aabbccddeeff00112233445566e3";
  private static final String SHARD = "https://sov-shard-28.example.workers.dev/";
  private static final String FILE = SHARD + "v1/files/e2/" + FILE_DIGEST + ".litematic";
  private static final String THUMBNAIL =
      SHARD + "v1/images/e6/" + THUMBNAIL_DIGEST + ".thumb96.png";

  @Test
  void absoluteShardAddressesAreStoredCompactlyAndReadBackTheSame() {
    DesignAddresses addresses = DesignAddresses.of(FILE, THUMBNAIL);
    assertEquals(FILE, addresses.primaryFileUrl());
    assertEquals(THUMBNAIL, addresses.thumbnailPngUrl());
    assertTrue(addresses.fileShape() >= 0);
    assertTrue(addresses.thumbnailShape() >= 0);
    assertEquals(FILE_DIGEST, addresses.primaryFileDigest());
    assertTrue(addresses.hasThumbnail());
  }

  @Test
  void relativeAddressesAndEveryFileKindAreCompact() {
    for (String suffix : new String[] {".litematic", ".schematic", ".schem", ".nbt"}) {
      String file = "v1/files/e2/" + FILE_DIGEST + suffix;
      DesignAddresses addresses = DesignAddresses.of(file, null);
      assertEquals(file, addresses.primaryFileUrl());
      assertTrue(addresses.fileShape() >= 0, suffix);
      assertEquals("", addresses.fileText());
    }
  }

  /** 60,000 designs share each host root's one string. */
  @Test
  void designsOnOneHostShareItsRoot() {
    String root = new String(SHARD.toCharArray());
    DesignAddresses first = DesignAddresses.of(FILE, null);
    DesignAddresses second =
        DesignAddresses.of(root + "v1/files/e2/" + FILE_DIGEST + ".nbt", THUMBNAIL);
    assertSame(first.fileText(), second.fileText());
    assertSame(second.fileText(), second.thumbnailText());
  }

  @Test
  void addressesOutsideThePatternAreKeptVerbatim() {
    String[] others = {
      "v1/images/b.png",
      "v1/files/83/my-farm.litematic",
      "v1/files/aa/" + FILE_DIGEST + ".litematic",
      "v1/files/E2/" + FILE_DIGEST.toUpperCase(Locale.ROOT) + ".litematic",
      "v1/files/e2/" + FILE_DIGEST + ".zip",
      "v1/images/e6/" + THUMBNAIL_DIGEST + ".thumb256.png",
      "v1/other/e2/" + FILE_DIGEST + ".litematic",
      "https://host.example/deep/v1/files/e2/" + FILE_DIGEST + ".litematic",
      "https://"
    };
    for (String other : others) {
      DesignAddresses addresses = DesignAddresses.of(other, other);
      assertEquals(DesignAddresses.VERBATIM, addresses.fileShape(), other);
      assertEquals(other, addresses.primaryFileUrl());
      assertEquals(other, addresses.thumbnailPngUrl());
      assertNull(addresses.primaryFileDigest());
    }
  }

  @Test
  void absentAddressesStayAbsent() {
    DesignAddresses addresses = DesignAddresses.of(null, null);
    assertNull(addresses.primaryFileUrl());
    assertNull(addresses.thumbnailPngUrl());
    assertNull(addresses.primaryFileDigest());
    assertFalse(addresses.hasThumbnail());
    assertEquals(DesignAddresses.NONE, addresses);
  }

  @Test
  void equalAddressesAreEqualAndOthersAreNot() {
    assertEquals(DesignAddresses.of(FILE, THUMBNAIL), DesignAddresses.of(FILE, THUMBNAIL));
    assertEquals(
        DesignAddresses.of(FILE, THUMBNAIL).hashCode(),
        DesignAddresses.of(FILE, THUMBNAIL).hashCode());
    assertNotEquals(DesignAddresses.of(FILE, THUMBNAIL), DesignAddresses.of(FILE, null));
    assertNotEquals(DesignAddresses.of(FILE, null), DesignAddresses.of(null, FILE));
  }

  @Test
  void summariesKeepTheirAddressAccessors() {
    SchematicSummary design = Samples.schematic("a", "A", "farms", 1, "2026-10-01T00:00:00Z");
    assertEquals("v1/files/aa/a.litematic", design.primaryFileUrl());
    assertNull(design.thumbnailPngUrl());
    assertFalse(design.hasThumbnail());
  }
}
