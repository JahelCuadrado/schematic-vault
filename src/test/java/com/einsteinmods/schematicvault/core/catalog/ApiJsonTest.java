package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiJsonTest {
  private static final String SHA =
      "e75be6953bfd6dbcdae7c2ae1857456c70368e67969e109f22a70e11bbb0482f";

  @Test
  void manifestGivesRevisionPagesAndEndpoints() throws CatalogFormatException {
    Manifest manifest =
        ApiJson.manifest(
            """
            {"apiVersion":"1.1.0","revision":"23f8","generatedAt":"2026-10-28T16:54:12Z",
             "pagination":{"pageSize":100,"pageCount":64},
             "endpoints":{"page":"v1/schematics/pages/{page}.json","future":"v9/x"},
             "languages":["en","es"]}
            """);
    assertEquals("23f8", manifest.revision());
    assertEquals(64, manifest.pageCount());
    assertEquals("v1/schematics/pages/{page}.json", manifest.pageTemplate());
    assertEquals(ApiJson.DEFAULT_SCHEMATIC_TEMPLATE, manifest.schematicTemplate());
    assertEquals(List.of("en", "es"), manifest.languages());
  }

  @Test
  void manifestGivesEachShardsHostInOrder() throws CatalogFormatException {
    Manifest manifest =
        ApiJson.manifest(
            """
            {"revision":"r","pagination":{"pageCount":1},
             "shards":{"count":32,"urlTemplate":"https://sov-shard-{shard}.example.dev/"}}
            """);
    assertEquals(32, manifest.shardRoots().size());
    assertEquals("https://sov-shard-00.example.dev/", manifest.shardRoots().getFirst());
    assertEquals("https://sov-shard-31.example.dev/", manifest.shardRoots().getLast());
    assertEquals(
        List.of(),
        ApiJson.manifest("{\"revision\":\"r\",\"pagination\":{\"pageCount\":1}}").shardRoots());
  }

  @Test
  void manifestGivesTheIndexOnlyWithItsEndpointAndPartCount() throws CatalogFormatException {
    Manifest manifest =
        ApiJson.manifest(
            """
            {"revision":"r","pagination":{"pageCount":603},
             "index":{"partCount":4,"partSize":20000},
             "endpoints":{"index":"v1/schematics/index/{part}.json"}}
            """);
    assertEquals(new IndexEndpoint("v1/schematics/index/{part}.json", 4), manifest.index());
    for (String partial :
        List.of(
            "\"index\":{\"partCount\":4}",
            "\"endpoints\":{\"index\":\"v1/schematics/index/{part}.json\"}",
            "\"index\":{\"partCount\":0},\"endpoints\":{\"index\":\"v1/i/{part}.json\"}",
            "\"index\":{\"partCount\":4},\"endpoints\":{\"index\":\"v1/i/all.json\"}")) {
      assertNull(
          ApiJson.manifest("{\"revision\":\"r\",\"pagination\":{\"pageCount\":1}," + partial + "}")
              .index(),
          partial);
    }
    assertThrows(
        CatalogFormatException.class,
        () ->
            ApiJson.manifest(
                "{\"revision\":\"r\",\"pagination\":{\"pageCount\":1},"
                    + "\"index\":{\"partCount\":4},"
                    + "\"endpoints\":{\"index\":\"../{part}.json\"}}"));
  }

  @Test
  void manifestWithUnsafeShardsIsRejected() {
    for (String shards :
        List.of(
            "{\"count\":2,\"urlTemplate\":\"http://s-{shard}.example.dev/\"}",
            "{\"count\":2,\"urlTemplate\":\"https://s-{shard}.example.dev:81/\"}",
            "{\"count\":2,\"urlTemplate\":\"https://s-{shard}.example.dev/x/\"}",
            "{\"count\":2,\"urlTemplate\":\"https://s.example.dev/\"}",
            "{\"count\":0,\"urlTemplate\":\"https://s-{shard}.example.dev/\"}")) {
      assertThrows(
          CatalogFormatException.class,
          () ->
              ApiJson.manifest(
                  "{\"revision\":\"r\",\"pagination\":{\"pageCount\":1},\"shards\":"
                      + shards
                      + "}"));
    }
  }

  @Test
  void pageKeepsShardAddresses() throws CatalogFormatException {
    SchematicSummary design =
        ApiJson.page(
                """
                {"items":[{"id":"abf-14645","title":"BdoublO Base",
                 "primaryFileUrl":"https://sov-shard-28.example.dev/v1/files/e2/e2.litematic",
                 "thumbnailPngUrl":"https://sov-shard-28.example.dev/v1/images/e6/e6.thumb96.png"}]}
                """)
            .getFirst();
    assertEquals(
        "https://sov-shard-28.example.dev/v1/files/e2/e2.litematic", design.primaryFileUrl());
    assertEquals(
        "https://sov-shard-28.example.dev/v1/images/e6/e6.thumb96.png", design.thumbnailPngUrl());
  }

  @Test
  void manifestWithoutRevisionOrWithUnsafeEndpointsIsRejected() {
    assertThrows(
        CatalogFormatException.class, () -> ApiJson.manifest("{\"pagination\":{\"pageCount\":1}}"));
    assertThrows(
        CatalogFormatException.class,
        () ->
            ApiJson.manifest(
                "{\"revision\":\"r\",\"pagination\":{\"pageCount\":1},"
                    + "\"endpoints\":{\"page\":\"https://evil.example/{page}\"}}"));
    assertThrows(CatalogFormatException.class, () -> ApiJson.manifest("not json"));
  }

  @Test
  void pageKeepsUsableItemsAndDropsUnsafePaths() throws CatalogFormatException {
    List<SchematicSummary> page =
        ApiJson.page(
            """
            {"page":1,"items":[
              {"id":"rdn-160","title":"160 pearl cannon","category":"technical/player-transport",
               "tags":["tnt"],"authors":["git_414"],"license":null,"sourceId":"redenmc",
               "minecraftVersion":"1.21.11","size":{"x":33,"y":36,"z":16},"blockCount":2283,
               "difficulty":"hard","verified":true,"width":6,
               "primaryFileUrl":"v1/files/ea/ea95.litematic","thumbnailUrl":"v1/images/x.webp",
               "updatedAt":"2026-10-24T01:45:51Z","somethingNew":{"a":1}},
              {"id":"no-title"},
              {"id":"bad-paths","title":"Bad","primaryFileUrl":"../../x",
               "thumbnailPngUrl":"https://evil.example/x.png"}
            ]}
            """);
    assertEquals(2, page.size());
    SchematicSummary cannon = page.getFirst();
    assertEquals("160 pearl cannon", cannon.title());
    assertEquals(new BlockSize(33, 36, 16), cannon.size());
    assertEquals(2283, cannon.blockCount());
    assertEquals(Difficulty.HARD, cannon.difficulty());
    assertTrue(cannon.verified());
    assertEquals(6, cannon.width());
    assertNull(page.get(1).difficulty());
    assertFalse(page.get(1).verified());
    assertEquals(0, page.get(1).width());
    assertNull(cannon.license());
    assertNull(cannon.thumbnailPngUrl());
    assertEquals("v1/files/ea/ea95.litematic", cannon.primaryFileUrl());
    assertNull(page.get(1).primaryFileUrl());
    assertNull(page.get(1).thumbnailPngUrl());
  }

  @Test
  void pageReadsWhatTheScoreUses() throws CatalogFormatException {
    List<SchematicSummary> page =
        ApiJson.page(
            """
            {"items":[
              {"id":"a","title":"A","status":"reviewed","dataVersionMax":4671,
               "summary":"Fast iron","popularity":87,"sourceStatus":"curated",
               "endorsementCount":4,"hasRates":true},
              {"id":"b","title":"B","status":"someday","dataVersionMax":null,"summary":" "},
              {"id":"c","title":"C","popularity":250,"sourceStatus":"someday",
               "endorsementCount":-2,"hasRates":"yes"},
              {"id":"d","title":"D","popularity":-3}
            ]}
            """);
    SchematicSummary rated = page.getFirst();
    assertEquals(ReviewStatus.REVIEWED, rated.status());
    assertEquals(4671, rated.dataVersion());
    assertTrue(rated.described());
    assertEquals(87, rated.popularity());
    assertEquals(SourceStatus.CURATED, rated.sourceStatus());
    assertEquals(4, rated.endorsementCount());
    assertTrue(rated.hasRates());
    SchematicSummary bare = page.get(1);
    assertNull(bare.status());
    assertEquals(0, bare.dataVersion());
    assertFalse(bare.described());
    assertEquals(SchematicSummary.NO_POPULARITY, bare.popularity());
    assertNull(bare.sourceStatus());
    assertEquals(0, bare.endorsementCount());
    assertFalse(bare.hasRates());
    assertEquals(SchematicSummary.MAX_POPULARITY, page.get(2).popularity());
    assertNull(page.get(2).sourceStatus());
    assertEquals(0, page.get(2).endorsementCount());
    assertFalse(page.get(2).hasRates());
    assertEquals(SchematicSummary.NO_POPULARITY, page.get(3).popularity());
  }

  @Test
  void taxonomyAndLocaleNames() throws CatalogFormatException {
    List<CategoryNode> nodes =
        ApiJson.taxonomy(
            """
            {"nodes":[{"id":"farms","path":"farms","name":"Farms","count":940,"children":[
              {"id":"mob","path":"farms/mob","name":"Mob farms","count":378,"children":[]}]}]}
            """);
    assertEquals("Mob farms", nodes.getFirst().children().getFirst().name());
    LocaleTexts locale =
        ApiJson.locale(
            "es",
            """
            {"language":"es","categories":{"farms":{"name":"Granjas"},"x":{}},
             "titles":{"a":"Granja de hierro","b":" ","c":3},
             "tags":{"iron-golem":"Gólem de hierro"},"tagGroups":{"build":"Tipo"}}
            """);
    assertEquals("es", locale.language());
    assertEquals(Map.of("farms", "Granjas"), locale.categoryNames());
    assertEquals(Map.of("a", "Granja de hierro"), locale.titles());
    assertEquals(Map.of("iron-golem", "Gólem de hierro"), locale.tagNames());
  }

  @Test
  void detailReadsItsTextsAndTranslations() throws CatalogFormatException {
    SchematicDetail detail =
        ApiJson.detail(
            """
            {"id":"x","title":"Piston Factory","description":"Makes pistons.",
             "summary":"###### Features\n**Fast**",
             "translations":{"es":{"title":"Fábrica de pistones","description":"Hace pistones."},
               "de":"broken"}}
            """);
    assertEquals(new DetailText("Piston Factory", "Makes pistons."), detail.original());
    assertEquals("###### Features\n**Fast**", detail.summary());
    assertEquals(
        Map.of("es", new DetailText("Fábrica de pistones", "Hace pistones.")),
        detail.translations());
  }

  @Test
  void detailKeepsSafeFilesInKnownFormats() throws CatalogFormatException {
    SchematicDetail detail =
        ApiJson.detail(
            """
            {"id":"aca-1","authors":[{"name":"Squishy","role":"designer","url":null}],
             "source":{"name":"Autocrafting Archive","url":"https://discord.com/channels/1"},
             "primaryFileId":"f2",
             "files":[
               {"id":"f1","fileName":"A.litematic","format":"litematic","sizeBytes":10,
                "sha256":"%s","url":"v1/files/e7/a.litematic"},
               {"id":"f2","fileName":"B.schem","format":"sponge_schem","sizeBytes":20,
                "sha256":"%s","url":"v1/files/e7/b.schem"},
               {"id":"f3","fileName":"C.zip","format":"zip","sha256":"%s","url":"v1/files/c.zip"},
               {"id":"f4","fileName":"D.nbt","format":"structure_nbt","sha256":"short","url":"v1/files/d.nbt"},
               {"id":"f5","fileName":"E.nbt","format":"structure_nbt","sha256":"%s","url":"../e.nbt"}
             ]}
            """
                .formatted(SHA.toUpperCase(Locale.ROOT), SHA, SHA, SHA));
    assertEquals("Squishy", detail.authors().getFirst().name());
    assertEquals("Autocrafting Archive", detail.sourceName());
    assertEquals("https://discord.com/channels/1", detail.sourceUrl());
    assertEquals(2, detail.files().size());
    assertEquals(SHA, detail.files().getFirst().sha256());
    assertEquals(SchematicFormat.SCHEM, detail.primaryFile().orElseThrow().format());
  }

  @Test
  void detailReadsEveryFormatTheApiPublishes() throws CatalogFormatException {
    SchematicDetail detail =
        ApiJson.detail(
            """
            {"id":"x","primaryFileId":"s","files":[
              {"id":"l","fileName":"A.litematic","format":"litematic","sha256":"%1$s",
               "url":"v1/files/a.litematic"},
              {"id":"s","fileName":"B.schem","format":"sponge_schem","sha256":"%1$s",
               "url":"v1/files/b.schem"},
              {"id":"m","fileName":"C.schematic","format":"mcedit_schematic","sha256":"%1$s",
               "url":"v1/files/c.schematic"},
              {"id":"n","fileName":"D.nbt","format":"structure_nbt","sha256":"%1$s",
               "url":"v1/files/d.nbt"},
              {"id":"e","fileName":"E.schem","format":"schem","sha256":"%1$s",
               "url":"v1/files/e.schem"}
            ]}
            """
                .formatted(SHA));
    assertEquals(
        List.of(
            SchematicFormat.LITEMATIC,
            SchematicFormat.SCHEM,
            SchematicFormat.SCHEMATIC,
            SchematicFormat.NBT),
        detail.files().stream().map(SchematicFile::format).toList());
    assertEquals("s", detail.primaryFile().orElseThrow().id());
  }

  @Test
  void detailFilesKeepTheirMinecraftVersion() throws CatalogFormatException {
    SchematicDetail detail =
        ApiJson.detail(
            """
            {"id":"x","files":[
              {"id":"l","fileName":"A.litematic","format":"litematic","sha256":"%1$s",
               "url":"v1/files/a.litematic","minecraftVersion":"1.20.6"},
              {"id":"s","fileName":"B.schem","format":"sponge_schem","sha256":"%1$s",
               "url":"v1/files/b.schem","minecraftVersion":null}
            ]}
            """
                .formatted(SHA));
    assertEquals("1.20.6", detail.files().getFirst().minecraftVersion());
    assertNull(detail.files().getLast().minecraftVersion());
  }

  @Test
  void detailDropsNonWebSourceLinks() throws CatalogFormatException {
    SchematicDetail detail =
        ApiJson.detail("{\"id\":\"x\",\"source\":{\"name\":\"S\",\"url\":\"file:///etc/passwd\"}}");
    assertNull(detail.sourceUrl());
    assertEquals(List.of(), detail.files());
  }
}
