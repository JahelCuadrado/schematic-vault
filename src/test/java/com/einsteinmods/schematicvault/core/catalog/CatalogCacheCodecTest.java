package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CatalogCacheCodecTest {
  private static final String API = Samples.API;
  private static final String OTHER_API = "https://mirror.example.net/";
  private static final String DIGEST =
      "e2310b5e8f3a4c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9012345678d4";
  private static final String SHARD = "https://sov-shard-28.example.workers.dev/";
  private static final String SHARD_FILE = SHARD + "v1/files/e2/" + DIGEST + ".schematic";
  private static final String SHARD_THUMBNAIL = SHARD + "v1/images/e2/" + DIGEST + ".thumb96.png";

  private static final Catalog CATALOG =
      new Catalog(
          "rev1",
          "2026-10-28T16:54:12Z",
          List.of(
              Samples.schematic(
                  "a",
                  "Granja \"de\" hierro",
                  "farms/mob/iron",
                  900,
                  "2026-10-01T00:00:00Z",
                  "iron"),
              shardDesign("c", SHARD_FILE, SHARD_THUMBNAIL),
              shardDesign("d", "v1/files/e2/" + DIGEST + ".nbt", "v1/images/odd-name.png"),
              new SchematicSummary(
                  "b",
                  "No extras",
                  List.of(),
                  List.of(),
                  "",
                  "MIT",
                  "",
                  null,
                  BlockSize.UNKNOWN,
                  0,
                  null,
                  true,
                  0,
                  null,
                  "v1/images/b.png",
                  "",
                  ReviewStatus.REVIEWED,
                  4671,
                  true,
                  87,
                  SourceStatus.CURATED,
                  4,
                  true)),
          List.of(
              new CategoryNode(
                  "farms",
                  "Farms",
                  940,
                  List.of(new CategoryNode("farms/mob", "Mob farms", 378, List.of())))),
          new LocaleTexts(
              "es",
              Map.of("farms", "Granjas"),
              Map.of("a", "Granja de hierro \"grande\""),
              Map.of("iron", "Hierro")));

  private static SchematicSummary shardDesign(
      String id, @Nullable String file, @Nullable String thumbnail) {
    return new SchematicSummary(
        id,
        "Shard " + id,
        List.of("Señor Builder", "Other"),
        List.of("iron", "compact"),
        "farms/mob/iron",
        "CC-BY-4.0",
        "planet-minecraft",
        null,
        new BlockSize(-1, 300, 70_000),
        Long.MAX_VALUE,
        Difficulty.HARD,
        false,
        12,
        file,
        thumbnail,
        "2026-10-01T00:00:00Z",
        null,
        -1,
        false,
        SchematicSummary.NO_POPULARITY,
        null,
        0,
        false);
  }

  private static byte[] write(Catalog catalog) throws IOException {
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    CatalogCacheCodec.write(catalog, API, written);
    return written.toByteArray();
  }

  private static Catalog read(byte[] cache) throws IOException, CatalogFormatException {
    return CatalogCacheCodec.read(new ByteArrayInputStream(cache), API);
  }

  @Test
  void writesAndReadsTheSameCatalog() throws IOException, CatalogFormatException {
    Catalog read = read(write(CATALOG));
    assertEquals(CATALOG, read);
    assertEquals("Granja de hierro \"grande\"", read.locale().title(read.schematics().getFirst()));
    assertEquals("Hierro", read.locale().tagName("iron"));
  }

  /** Relative, absolute, verbatim and absent addresses come back as the same strings. */
  @Test
  void addressesReadBackAsTheSameStrings() throws IOException, CatalogFormatException {
    List<SchematicSummary> read = read(write(CATALOG)).schematics();
    assertEquals("v1/files/aa/a.litematic", read.get(0).primaryFileUrl());
    assertNull(read.get(0).thumbnailPngUrl());
    assertEquals(SHARD_FILE, read.get(1).primaryFileUrl());
    assertEquals(SHARD_THUMBNAIL, read.get(1).thumbnailPngUrl());
    assertEquals("v1/files/e2/" + DIGEST + ".nbt", read.get(2).primaryFileUrl());
    assertEquals("v1/images/odd-name.png", read.get(2).thumbnailPngUrl());
    assertNull(read.get(3).primaryFileUrl());
    assertEquals(DIGEST, read.get(1).addresses().primaryFileDigest());
  }

  /** Repeated texts are stored once and shared by every design that reads them. */
  @Test
  void repeatedStringsAreShared() throws IOException, CatalogFormatException {
    List<SchematicSummary> read = read(write(CATALOG)).schematics();
    assertSame(read.get(1).category(), read.get(2).category());
    assertSame(read.get(1).authors().getFirst(), read.get(0).authors().getFirst());
  }

  /** The header alone: what a background check needs, without the designs after it. */
  @Test
  void theHeaderReadsWithoutTheRestOfTheFile() throws IOException, CatalogFormatException {
    byte[] cache = write(CATALOG);
    byte[] headerOnly = Arrays.copyOf(cache, headerLength());
    CatalogCacheCodec.Header header =
        CatalogCacheCodec.readHeader(new ByteArrayInputStream(headerOnly));
    assertEquals(new CatalogCacheCodec.Header(API, "rev1", "es", "2026-10-28T16:54:12Z"), header);
  }

  /** The player changed the API address: the old address's designs never show for the new one. */
  @Test
  void aCacheOfAnotherApiAddressIsNoCache() throws IOException {
    byte[] cache = write(CATALOG);
    assertThrows(
        CatalogFormatException.class,
        () -> CatalogCacheCodec.read(new ByteArrayInputStream(cache), OTHER_API));
  }

  /** The JSON caches of format 6 and before: downloaded again once. */
  @Test
  void jsonCachesOfEarlierVersionsAreNoCache() {
    byte[] json =
        "{\"formatVersion\":6,\"apiBaseUrl\":\"https://example.org/api/\",\"revision\":\"r\"}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(CatalogFormatException.class, () -> read(json));
    assertThrows(
        CatalogFormatException.class,
        () -> CatalogCacheCodec.readHeader(new ByteArrayInputStream(json)));
  }

  @Test
  void otherFormatVersionsAndEmptyFilesAreNoCache() throws IOException {
    byte[] cache = write(CATALOG);
    cache[Integer.BYTES + Integer.BYTES - 1]--;
    assertThrows(CatalogFormatException.class, () -> read(cache));
    assertThrows(CatalogFormatException.class, () -> read(new byte[0]));
  }

  /** A file cut anywhere (a crash while copying it, a full disk) is no cache. */
  @Test
  void aTruncatedCacheIsNoCache() throws IOException {
    byte[] cache = write(CATALOG);
    for (int length = 0; length < cache.length; length++) {
      byte[] cut = Arrays.copyOf(cache, length);
      assertThrows(CatalogFormatException.class, () -> read(cut), "cut at " + length);
    }
  }

  /** A huge count in a corrupt file fails before allocating anything for it. */
  @Test
  void aCorruptCountFailsWithoutAHugeAllocation() throws IOException {
    byte[] cache = write(CATALOG);
    byte[] corrupt = Arrays.copyOf(cache, headerLength() + 5);
    int count = headerLength();
    // Varint 0x7FFFFFFF: over the string table's cap.
    corrupt[count] = (byte) 0xFF;
    corrupt[count + 1] = (byte) 0xFF;
    corrupt[count + 2] = (byte) 0xFF;
    corrupt[count + 3] = (byte) 0xFF;
    corrupt[count + 4] = 0x07;
    assertThrows(CatalogFormatException.class, () -> read(corrupt));
    // Just under the cap, with no strings after it: ends early, nothing preallocated.
    corrupt[count + 3] = 0x03;
    corrupt[count + 4] = 0x00;
    assertThrows(CatalogFormatException.class, () -> read(corrupt));
  }

  /** Any single damaged byte reads as a catalog or as no cache: never another failure. */
  @Test
  void aDamagedByteIsACatalogOrNoCache() throws IOException {
    byte[] cache = write(CATALOG);
    for (int index = 0; index < cache.length; index++) {
      byte[] damaged = cache.clone();
      damaged[index] ^= (byte) 0xA5;
      try {
        read(damaged);
      } catch (CatalogFormatException expected) {
        // No cache: the vault downloads the catalog again.
        assertEquals(CatalogFormatException.class, expected.getClass());
      }
    }
  }

  @Test
  void unsafeAddressesInAnEditedCacheAreDropped() throws IOException, CatalogFormatException {
    Catalog edited =
        new Catalog(
            "r",
            "",
            List.of(
                shardDesign(
                    "x",
                    "https://evil.example/x",
                    "https://EVIL.example/v1/images/e2/" + DIGEST + ".thumb96.png")),
            List.of(),
            LocaleTexts.NONE);
    SchematicSummary schematic = read(write(edited)).schematics().getFirst();
    assertNull(schematic.primaryFileUrl());
    assertNull(schematic.thumbnailPngUrl());
  }

  /** Bytes of the magic, the version and the header's four strings of {@link #CATALOG}. */
  private static int headerLength() {
    int length = Integer.BYTES * 2;
    for (String text : new String[] {API, "rev1", "es", "2026-10-28T16:54:12Z"}) {
      length += 1 + text.getBytes(StandardCharsets.UTF_8).length;
    }
    return length;
  }

  @Test
  void categoryNamesFallBackToEnglish() {
    CategoryNode farms = CATALOG.taxonomy().getFirst();
    assertEquals("Granjas", CATALOG.categoryName(farms));
    assertEquals("Mob farms", CATALOG.categoryName(farms.children().getFirst()));
    List<CategoryRow> rows = CategoryTree.rows(CATALOG);
    assertEquals(
        List.of(
            CategoryRow.all(4),
            new CategoryRow("farms", "Granjas", 0, 940),
            new CategoryRow("farms/mob", "Mob farms", 1, 378)),
        rows);
  }
}
