package com.einsteinmods.schematicvault.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.client.net.FailureKind;
import com.einsteinmods.schematicvault.core.catalog.SchematicSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which source of designs an open reads, on a small local copy of the API. */
class CatalogRepositoryIndexTest {
  private static final String ONLINE = "https://example.org/";
  private static final String LANGUAGE = "en_us";
  private static final String PAGES_MANIFEST =
      "{\"revision\":\"r1\",\"pagination\":{\"pageCount\":1}}";
  private static final String INDEX_MANIFEST =
      """
      {"revision":"r1","pagination":{"pageCount":1},"index":{"partCount":2,"partSize":1},
       "endpoints":{"index":"v1/schematics/index/{part}.json"}}
      """;

  @TempDir Path temp;
  @TempDir Path cacheDirectory;
  private Path root;

  @BeforeEach
  void createCopy() throws IOException {
    root = temp.toRealPath();
    write("v1/taxonomy.json", "{\"nodes\":[]}");
    write("v1/schematics/pages/1.json", "{\"items\":[{\"id\":\"p-1\",\"title\":\"Page\"}]}");
  }

  @Test
  void withoutAnIndexInTheManifestThePagesAreRead() throws IOException {
    write("v1/manifest.json", PAGES_MANIFEST);
    writeIndex();
    assertEquals(List.of("p-1"), ids(open().designs()));
  }

  @Test
  void theIndexIsReadInPartOrderWithProgressPerPart() throws IOException {
    write("v1/manifest.json", INDEX_MANIFEST);
    writeIndex();
    Opened opened = open();
    assertEquals(List.of("i-1", "i-2"), ids(opened.designs()));
    assertEquals(List.of(0, 1, 2), opened.progress());
  }

  @Test
  void aCopyWithoutTheIndexFallsBackToThePages() throws IOException {
    write("v1/manifest.json", INDEX_MANIFEST);
    assertEquals(List.of("p-1"), ids(open().designs()));
  }

  @Test
  void theBackgroundRefreshCachesTheIndex() throws IOException {
    write("v1/manifest.json", INDEX_MANIFEST);
    writeIndex();
    ApiClient api = ApiClient.localCopy(root);
    CatalogCache cache = new CatalogCache(cacheDirectory, api.cacheKey(ONLINE));
    new CatalogRepository(api, cache, Runnable::run).refreshCache(LANGUAGE).join();
    assertEquals(List.of("i-1", "i-2"), ids(cache.read().orElseThrow().schematics()));
  }

  private void writeIndex() throws IOException {
    write(
        "v1/schematics/index/1.json",
        "{\"part\":1,\"partCount\":2,\"total\":2,\"fields\":[\"id\",\"title\"],"
            + "\"rows\":[[\"i-1\",\"One\"]]}");
    write(
        "v1/schematics/index/2.json",
        "{\"part\":2,\"partCount\":2,\"total\":2,\"fields\":[\"id\",\"title\"],"
            + "\"rows\":[[\"i-2\",\"Two\"]]}");
  }

  private void write(String path, String json) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, json);
  }

  private Opened open() {
    ApiClient api = ApiClient.localCopy(root);
    CatalogRepository repository =
        new CatalogRepository(
            api, new CatalogCache(cacheDirectory, api.cacheKey(ONLINE)), Runnable::run);
    CompletableFuture<List<SchematicSummary>> done = new CompletableFuture<>();
    // Reported from the I/O pool's two threads: order between them is not fixed.
    List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
    repository.open(
        LANGUAGE,
        new CatalogListener() {
          @Override
          public void onCacheRead(@Nullable LoadedCatalog cached) {}

          @Override
          public void onProgress(int partsDone, int total) {
            progress.add(partsDone);
          }

          @Override
          public void onCurrent() {
            done.completeExceptionally(new IllegalStateException("nothing downloaded"));
          }

          @Override
          public void onUpdated(LoadedCatalog catalog) {
            done.complete(catalog.catalog().schematics());
          }

          @Override
          public void onFailed(FailureKind kind) {
            done.completeExceptionally(new IllegalStateException(kind.toString()));
          }
        });
    List<SchematicSummary> designs = done.join();
    return new Opened(designs, progress.stream().sorted().toList());
  }

  private static List<String> ids(List<SchematicSummary> designs) {
    return designs.stream().map(SchematicSummary::id).toList();
  }

  private record Opened(List<SchematicSummary> designs, List<Integer> progress) {}
}
