package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CatalogSearchTest {
  private static final long WAIT_SECONDS = 5;
  private static final long NO_MORE_MILLIS = 100;
  private static final CatalogIndex INDEX = CatalogRefineTest.INDEX;

  @Test
  void eachLetterTypedGivesTheFullSearchResult() {
    try (Closing closing = new Closing(new CatalogSearch(Runnable::run))) {
      String typed = "granja de hierro";
      for (int length = 0; length <= typed.length(); length++) {
        CatalogFilter filter = CatalogFilter.NONE.withText(typed.substring(0, length));
        assertArrayEquals(INDEX.filter(filter), closing.search.filterNow(INDEX, filter));
      }
      for (int length = typed.length(); length >= 0; length--) {
        CatalogFilter filter = CatalogFilter.NONE.withText(typed.substring(0, length));
        assertArrayEquals(INDEX.filter(filter), closing.search.filterNow(INDEX, filter));
      }
    }
  }

  @Test
  void onlyTheNewestRequestIsDelivered() throws InterruptedException {
    BlockingQueue<Runnable> renderThread = new LinkedBlockingQueue<>();
    List<String> delivered = new ArrayList<>();
    try (Closing closing = new Closing(new CatalogSearch(renderThread::add))) {
      for (String query : new String[] {"i", "ir", "iro", "iron"}) {
        closing.search.request(
            INDEX, CatalogFilter.NONE.withText(query), matches -> delivered.add(query));
      }
      runUntilDelivered(renderThread, delivered);
      assertEquals(List.of("iron"), delivered);
    }
  }

  @Test
  void aSearchOnTheCallersThreadDropsThePendingOnes() throws InterruptedException {
    BlockingQueue<Runnable> renderThread = new LinkedBlockingQueue<>();
    List<String> delivered = new ArrayList<>();
    try (Closing closing = new Closing(new CatalogSearch(renderThread::add))) {
      closing.search.request(INDEX, CatalogFilter.NONE.withText("gold"), m -> delivered.add("old"));
      CatalogFilter iron = CatalogFilter.NONE.withText("iron");
      assertArrayEquals(INDEX.filter(iron), closing.search.filterNow(INDEX, iron));
      closing.search.cancel();
      closing.search.request(INDEX, iron, matches -> delivered.add("new"));
      runUntilDelivered(renderThread, delivered);
      assertEquals(List.of("new"), delivered);
    }
  }

  @Test
  void aClosedSearchDeliversNothing() throws InterruptedException {
    BlockingQueue<Runnable> renderThread = new LinkedBlockingQueue<>();
    List<String> delivered = new ArrayList<>();
    CatalogSearch search = new CatalogSearch(renderThread::add);
    search.request(INDEX, CatalogFilter.NONE.withText("iron"), matches -> delivered.add("iron"));
    search.close();
    search.request(INDEX, CatalogFilter.NONE, matches -> delivered.add("after"));
    Runnable task;
    while ((task = renderThread.poll(NO_MORE_MILLIS, TimeUnit.MILLISECONDS)) != null) {
      task.run();
    }
    assertEquals(List.of(), delivered);
  }

  /** Runs what the search hands over until one result is delivered, then checks none follows. */
  private static void runUntilDelivered(
      BlockingQueue<Runnable> renderThread, List<String> delivered) throws InterruptedException {
    while (delivered.isEmpty()) {
      Runnable task = renderThread.poll(WAIT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(task, "no result delivered");
      task.run();
    }
    assertNull(renderThread.poll(NO_MORE_MILLIS, TimeUnit.MILLISECONDS));
  }

  /** Closes the search after a test. */
  private record Closing(CatalogSearch search) implements AutoCloseable {
    @Override
    public void close() {
      search.close();
    }
  }
}
