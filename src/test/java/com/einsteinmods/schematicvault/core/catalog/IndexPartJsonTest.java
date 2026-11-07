package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexPartJsonTest {
  private static final int SHARD_COUNT = 32;
  private static final List<String> SHARDS = shards();
  private static final String FIELDS =
      """
      ["id","title","authors","tags","category","license","size","blockCount","difficulty",
       "verified","width","updatedAt","status","dataVersionMax","hasSummary","popularity",
       "sourceStatus","endorsementCount","hasRates","file","thumbnail"]
      """;
  private static final String FILE =
      "e23159a0abf8311821160a04399e18fa740a20b569e5c46068a57f78d20132d4.litematic";
  private static final String THUMBNAIL =
      "e6a0adcecd4dff327f60c875675aa5445f0426d993388b4accf57cdec463473e.thumb96.png";

  /** Row {@code abf-14645} of the live index (API 2.1.0, revision 56460fcbc45c4545). */
  private static final String ROW =
      """
      ["abf-14645","BdoublO Base",["BdoubleO100"],["wood","terrain","realistic","rustic"],
       "builds/houses",null,{"x":29,"y":27,"z":24},2841,"medium",false,24,
       "2026-10-27T05:01:25Z","unreviewed",4903,false,0,"community",0,false,"%s","%s"]
      """
          .formatted(FILE, THUMBNAIL);

  /** The same design in {@code v1/schematics/pages/1.json} of that revision (fields it reads). */
  private static final String PAGE_ITEM =
      """
      {"id":"abf-14645","title":"BdoublO Base","summary":null,"status":"unreviewed",
       "verified":false,"category":"builds/houses","tags":["wood","terrain","realistic","rustic"],
       "authors":["BdoubleO100"],"license":null,"sourceId":"abfielder","dataVersionMax":4903,
       "minecraftVersion":null,"size":{"x":29,"y":27,"z":24},"width":24,"blockCount":2841,
       "difficulty":"medium",
       "primaryFileUrl":"https://sov-shard-28.example.dev/v1/files/e2/%s",
       "thumbnailPngUrl":"https://sov-shard-28.example.dev/v1/images/e6/%s",
       "updatedAt":"2026-10-27T05:01:25Z","popularity":0,"sourceStatus":"community",
       "endorsementCount":0,"hasRates":false}
      """
          .formatted(FILE, THUMBNAIL);

  @Test
  void rowsReadAsThePagesReadTheSameDesign() throws CatalogFormatException {
    SchematicSummary fromIndex = only(read(part(FIELDS, ROW), SHARDS));
    SchematicSummary fromPage = ApiJson.page("{\"items\":[" + PAGE_ITEM + "]}").getFirst();
    assertEquals(withoutIndexGaps(fromPage), fromIndex);
    assertEquals(fromPage.primaryFileUrl(), fromIndex.primaryFileUrl());
    assertEquals(fromPage.thumbnailPngUrl(), fromIndex.thumbnailPngUrl());
    assertEquals(
        "https://sov-shard-28.example.dev/v1/files/e2/" + FILE, fromIndex.primaryFileUrl());
    assertEquals("", fromIndex.sourceId());
    assertNull(fromIndex.minecraftVersion());
  }

  @Test
  void columnsAreFoundByNameAndUnknownOnesSkipped() throws CatalogFormatException {
    String fields =
        "[\"future\",\"title\",\"id\",\"blockCount\",\"popularity\",\"file\",\"later\"]";
    String row = "[{\"a\":[1]},\"Farm\",\"x-1\",12,55,\"" + FILE + "\",[\"z\"],\"extra\"]";
    SchematicSummary design = only(read(part(fields, row), SHARDS));
    assertEquals("x-1", design.id());
    assertEquals("Farm", design.title());
    assertEquals(12, design.blockCount());
    assertEquals(55, design.popularity());
    assertEquals("https://sov-shard-28.example.dev/v1/files/e2/" + FILE, design.primaryFileUrl());
    // Columns not given read as absent, with the pages' defaults.
    assertEquals(List.of(), design.authors());
    assertEquals("", design.category());
    assertEquals(BlockSize.UNKNOWN, design.size());
    assertNull(design.difficulty());
    assertFalse(design.hasThumbnail());
    assertFalse(design.hasRates());
  }

  @Test
  void nullsAndOtherTypesReadAsAbsent() throws CatalogFormatException {
    String row =
        """
        ["x-2","T",null,"tag",null,null,null,null,null,null,null,null,null,null,null,null,
         null,null,null,null,null]
        """;
    SchematicSummary design = only(read(part(FIELDS, row), SHARDS));
    assertNull(design.license());
    assertEquals(SchematicSummary.NO_POPULARITY, design.popularity());
    assertEquals(0, design.dataVersion());
    assertEquals(List.of(), design.tags());
    assertNull(design.primaryFileUrl());
    assertNull(design.thumbnailPngUrl());
    assertNull(design.status());
    assertNull(design.sourceStatus());
  }

  @Test
  void rowsWithoutIdOrTitleAreLeftOut() throws CatalogFormatException {
    String fields = "[\"id\",\"title\"]";
    List<SchematicSummary> designs =
        read(
            part(fields, "[\"a\",\"A\"],[null,\"B\"],[\"\",\"C\"],[\"d\"],\"junk\",[\"e\",\"E\"]"),
            SHARDS);
    assertEquals(List.of("a", "e"), designs.stream().map(SchematicSummary::id).toList());
  }

  @Test
  void withoutShardsAddressesAreApiPaths() throws CatalogFormatException {
    SchematicSummary design = only(read(part(FIELDS, ROW), List.of()));
    assertEquals("v1/files/e2/" + FILE, design.primaryFileUrl());
    assertEquals("v1/images/e6/" + THUMBNAIL, design.thumbnailPngUrl());
  }

  @Test
  void otherNamesAreBuiltAndCheckedOrDropped() throws CatalogFormatException {
    String fields = "[\"id\",\"title\",\"file\",\"thumbnail\"]";
    String rows =
        "[\"a\",\"A\",\"0f.mcstructure\",\""
            + THUMBNAIL
            + "\"],"
            + "[\"b\",\"B\",\"0f/../x.nbt\",\"E6.png\"],"
            + "[\"c\",\"C\",\"x\",\"zz.png\"]";
    List<SchematicSummary> designs = read(part(fields, rows), SHARDS);
    assertEquals(
        "https://sov-shard-01.example.dev/v1/files/0f/0f.mcstructure",
        designs.get(0).primaryFileUrl());
    assertEquals(
        "https://sov-shard-28.example.dev/v1/images/e6/" + THUMBNAIL,
        designs.get(0).thumbnailPngUrl());
    assertNull(designs.get(1).primaryFileUrl());
    assertNull(designs.get(1).thumbnailPngUrl());
    assertNull(designs.get(2).primaryFileUrl());
    assertNull(designs.get(2).thumbnailPngUrl());
  }

  @Test
  void eachDigestsFirstByteNamesItsShard() throws CatalogFormatException {
    String fields = "[\"id\",\"title\",\"file\"]";
    String low = "00" + FILE.substring(2);
    String high = "ff" + FILE.substring(2);
    List<SchematicSummary> designs =
        read(part(fields, "[\"a\",\"A\",\"" + low + "\"],[\"b\",\"B\",\"" + high + "\"]"), SHARDS);
    assertTrue(designs.get(0).primaryFileUrl().startsWith("https://sov-shard-00.example.dev/"));
    assertTrue(designs.get(1).primaryFileUrl().startsWith("https://sov-shard-31.example.dev/"));
  }

  @Test
  void rowsBeforeFieldsStillReadByName() throws CatalogFormatException {
    String json =
        "{\"rows\":[[\"T\",\"x-3\"]],\"part\":1,\"fields\":[\"title\",\"id\"],\"partCount\":4}";
    SchematicSummary design = only(IndexPartJson.read(bytes(json), 1, 4, SHARDS));
    assertEquals("x-3", design.id());
    assertEquals("T", design.title());
  }

  @Test
  void anotherPartOrPartCountOrBrokenJsonFails() {
    assertThrows(
        CatalogFormatException.class,
        () -> IndexPartJson.read(bytes(part(FIELDS, ROW)), 2, 4, SHARDS));
    assertThrows(
        CatalogFormatException.class,
        () -> IndexPartJson.read(bytes(part(FIELDS, ROW)), 1, 5, SHARDS));
    assertThrows(
        CatalogFormatException.class,
        () -> IndexPartJson.read(bytes("{\"part\":1,\"partCount\":4,\"rows\":[]}"), 1, 4, SHARDS));
    assertThrows(
        CatalogFormatException.class,
        () -> IndexPartJson.read(bytes("{\"part\":1,\"rows\":[[\"a\""), 1, 4, SHARDS));
    assertThrows(CatalogFormatException.class, () -> IndexPartJson.read(bytes("[]"), 1, 4, SHARDS));
  }

  /** What the index cannot give: the pages' {@code sourceId} and {@code minecraftVersion}. */
  static SchematicSummary withoutIndexGaps(SchematicSummary page) {
    return new SchematicSummary(
        page.id(),
        page.title(),
        page.authors(),
        page.tags(),
        page.category(),
        page.license(),
        "",
        null,
        page.size(),
        page.blockCount(),
        page.difficulty(),
        page.verified(),
        page.width(),
        page.addresses(),
        page.updatedAt(),
        page.status(),
        page.dataVersion(),
        page.described(),
        page.popularity(),
        page.sourceStatus(),
        page.endorsementCount(),
        page.hasRates());
  }

  private static List<SchematicSummary> read(String json, List<String> shards)
      throws CatalogFormatException {
    return IndexPartJson.read(bytes(json), 1, 4, shards);
  }

  private static String part(String fields, String rows) {
    return "{\"part\":1,\"partCount\":4,\"total\":1,\"fields\":"
        + fields
        + ",\"rows\":["
        + rows
        + "]}";
  }

  private static SchematicSummary only(List<SchematicSummary> designs) {
    assertEquals(1, designs.size());
    return designs.getFirst();
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> shards() {
    List<String> roots = new ArrayList<>(SHARD_COUNT);
    for (int shard = 0; shard < SHARD_COUNT; shard++) {
      roots.add("https://sov-shard-%02d.example.dev/".formatted(shard));
    }
    return List.copyOf(roots);
  }
}
