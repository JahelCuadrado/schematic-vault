package com.einsteinmods.schematicvault.core.catalog;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Filters a {@link CatalogIndex} off the caller's thread, latest wins: each {@link #request} is
 * numbered, runs on one background thread of its own (never the I/O pool, whose downloads could
 * delay it) and hands its matches to the result executor only if no newer request, {@link
 * #filterNow} or {@link #cancel} came since; a request already outdated when its turn comes is not
 * computed at all. Until then the caller keeps showing the previous matches.
 *
 * <p>A filter that {@link CatalogFilter#refines refines} the last one computed (same index)
 * searches only that one's matches, so typing on in the search box gets cheaper with each letter.
 *
 * <p>Cost: no thread while idle (the thread starts with the first request and stops {@link
 * #IDLE_SECONDS} after the last one); per request a few small objects and one pass over the
 * catalog, or over the previous matches. Match arrays are shared, never changed. Thread-safe: the
 * index is immutable and the last result is one volatile triple.
 */
public final class CatalogSearch {
  /** How long the search thread waits for a new request before it stops. */
  static final long IDLE_SECONDS = 5;

  private static final String THREAD_NAME = "Schematic Vault search";

  private final Executor resultExecutor;
  private final ThreadPoolExecutor worker;
  private final AtomicLong newest = new AtomicLong();
  private volatile @Nullable Computed last;
  private volatile boolean closed;

  /**
   * @param resultExecutor where results are handed over (the render thread)
   */
  public CatalogSearch(Executor resultExecutor) {
    this.resultExecutor = resultExecutor;
    // No core thread: one starts on demand and stops when idle; one at most, requests queue.
    this.worker =
        new ThreadPoolExecutor(
            0,
            1,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(task));
  }

  /**
   * Filters on the caller's thread and drops every pending request (they belong to an older filter
   * or index).
   */
  public int[] filterNow(CatalogIndex index, CatalogFilter filter) {
    newest.incrementAndGet();
    return compute(index, filter);
  }

  /**
   * Filters on the search thread; {@code onResult} runs on the result executor with the matches,
   * unless something newer came first or the search was closed.
   */
  public void request(CatalogIndex index, CatalogFilter filter, Consumer<int[]> onResult) {
    if (closed) {
      return;
    }
    long sequence = newest.incrementAndGet();
    worker.execute(
        () -> {
          if (newest.get() != sequence) {
            return;
          }
          int[] matches = compute(index, filter);
          resultExecutor.execute(
              () -> {
                if (!closed && newest.get() == sequence) {
                  onResult.accept(matches);
                }
              });
        });
  }

  /** Drops every pending request. */
  public void cancel() {
    newest.incrementAndGet();
  }

  /** Drops every pending request and stops the thread; later requests do nothing. */
  public void close() {
    closed = true;
    newest.incrementAndGet();
    last = null;
    worker.shutdownNow();
  }

  private int[] compute(CatalogIndex index, CatalogFilter filter) {
    Computed previous = last;
    int[] matches =
        previous != null && previous.index == index && filter.refines(previous.filter)
            ? index.refine(filter, previous.matches)
            : index.filter(filter);
    if (!closed) {
      last = new Computed(index, filter, matches);
    }
    return matches;
  }

  /** The last filter computed, with the index it ran on and its matches. */
  private record Computed(CatalogIndex index, CatalogFilter filter, int[] matches) {}
}
