package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogCacheHeaderTest {
  private static final String API = Samples.API;

  /** A local API copy's cache key: its folder's {@code file:} URI. */
  private static final String LOCAL_COPY = "file:///home/player/api-copy/public/";

  private static final Catalog CATALOG =
      new Catalog(
          "rev7",
          "2026-11-05T16:47:50Z",
          List.of(Samples.schematic("a", "A", "farms", 1, "2026-10-01T00:00:00Z")),
          List.of(),
          new LocaleTexts("es", Map.of(), Map.of(), Map.of()));

  @Test
  void readsTheRevisionAndLanguageTheCodecWrote() throws IOException {
    assertEquals(
        Optional.of(new CatalogCacheHeader("rev7", "es")),
        CatalogCacheHeader.read(new ByteArrayInputStream(written(API)), API));
  }

  @Test
  void aLocalCopysFileAddressIsCheckedLikeAnyOther() throws IOException {
    byte[] cache = written(LOCAL_COPY);
    assertEquals(
        Optional.of(new CatalogCacheHeader("rev7", "es")),
        CatalogCacheHeader.read(new ByteArrayInputStream(cache), LOCAL_COPY));
    assertTrue(CatalogCacheHeader.read(new ByteArrayInputStream(cache), API).isEmpty());
  }

  @Test
  void stopsBeforeTheDesigns() throws IOException {
    byte[] cache = written(API);
    int header = Integer.BYTES * 2;
    for (String text : new String[] {API, "rev7", "es", "2026-11-05T16:47:50Z"}) {
      header += 1 + text.getBytes(StandardCharsets.UTF_8).length;
    }
    // Cut right after the header: it is read without reaching the strings or the designs.
    assertEquals(
        Optional.of(new CatalogCacheHeader("rev7", "es")),
        CatalogCacheHeader.read(new ByteArrayInputStream(Arrays.copyOf(cache, header)), API));
  }

  @Test
  void anotherAddressOrFormatHasNoHeader() throws IOException {
    assertTrue(
        CatalogCacheHeader.read(new ByteArrayInputStream(written(API)), "https://other.example/")
            .isEmpty());
    byte[] otherVersion = written(API);
    otherVersion[Integer.BYTES * 2 - 1]--;
    assertTrue(CatalogCacheHeader.read(new ByteArrayInputStream(otherVersion), API).isEmpty());
    byte[] json =
        ("{\"formatVersion\":6,\"apiBaseUrl\":\"" + API + "\",\"revision\":\"r\"}")
            .getBytes(StandardCharsets.UTF_8);
    assertTrue(CatalogCacheHeader.read(new ByteArrayInputStream(json), API).isEmpty());
    assertTrue(
        CatalogCacheHeader.read(new ByteArrayInputStream(Arrays.copyOf(written(API), 12)), API)
            .isEmpty());
  }

  @Test
  void plansFromTheHeaderAsFromTheCatalog() {
    CatalogCacheHeader header = new CatalogCacheHeader("rev7", "es");
    assertEquals(RefreshPlan.FULL, RefreshPlan.fromHeader(null, "rev7", "es"));
    assertEquals(RefreshPlan.FULL, RefreshPlan.fromHeader(header, "rev8", "es"));
    assertEquals(RefreshPlan.LOCALE_ONLY, RefreshPlan.fromHeader(header, "rev7", "de"));
    assertEquals(RefreshPlan.UP_TO_DATE, RefreshPlan.fromHeader(header, "rev7", "es"));
  }

  private static byte[] written(String apiBaseUrl) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    CatalogCacheCodec.write(CATALOG, apiBaseUrl, output);
    return output.toByteArray();
  }
}
