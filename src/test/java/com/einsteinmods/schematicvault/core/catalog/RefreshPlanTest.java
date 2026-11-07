package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefreshPlanTest {
  private static final String API = Samples.API;

  private static final Catalog CACHED =
      new Catalog(
          "rev1",
          "2026-10-28T16:54:12Z",
          List.of(),
          List.of(),
          new LocaleTexts("es", Map.of(), Map.of(), Map.of()));

  @Test
  void noCacheOrNewRevisionDownloadsEverything() {
    assertEquals(RefreshPlan.FULL, RefreshPlan.decide(null, "rev1", "es"));
    assertEquals(RefreshPlan.FULL, RefreshPlan.decide(CACHED, "rev2", "es"));
  }

  @Test
  void sameRevisionDownloadsNothingOrOnlyTheLocale() {
    assertEquals(RefreshPlan.UP_TO_DATE, RefreshPlan.decide(CACHED, "rev1", "es"));
    assertEquals(RefreshPlan.LOCALE_ONLY, RefreshPlan.decide(CACHED, "rev1", "de"));
  }

  @Test
  void anUnreadLocaleIsAskedForAgain() {
    Catalog withoutLocale = CACHED.withLocale(LocaleTexts.NONE);
    assertEquals(RefreshPlan.LOCALE_ONLY, RefreshPlan.decide(withoutLocale, "rev1", "en"));
  }

  /**
   * A new revision with the language unchanged still refetches everything, and the catalog built
   * from the new pages alone replaces the cached one: a design the API removed is gone after the
   * cache is written and read again (nothing is merged with the old cache).
   */
  @Test
  void newRevisionReplacesTheCachedCatalog() throws IOException, CatalogFormatException {
    Catalog cached =
        new Catalog(
            "old",
            "2026-10-27T00:00:00Z",
            List.of(
                Samples.schematic("kept", "Kept", "farms", 1, "2026-10-01T00:00:00Z"),
                Samples.schematic("removed", "Removed", "farms", 1, "2026-10-01T00:00:00Z")),
            List.of(),
            new LocaleTexts("es", Map.of(), Map.of("removed", "Quitado"), Map.of()));
    assertEquals(RefreshPlan.FULL, RefreshPlan.decide(cached, "new", "es"));

    Catalog refreshed =
        new Catalog(
            "new",
            "2026-10-29T00:00:00Z",
            List.of(Samples.schematic("kept", "Kept", "farms", 1, "2026-10-01T00:00:00Z")),
            List.of(),
            new LocaleTexts("es", Map.of(), Map.of(), Map.of()));
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    CatalogCacheCodec.write(refreshed, API, written);
    Catalog reread = CatalogCacheCodec.read(new ByteArrayInputStream(written.toByteArray()), API);

    assertEquals(refreshed, reread);
    assertEquals(RefreshPlan.UP_TO_DATE, RefreshPlan.decide(reread, "new", "es"));
    assertFalse(reread.schematics().stream().anyMatch(design -> design.id().equals("removed")));
  }
}
