package com.einsteinmods.schematicvault.client.net;

import com.einsteinmods.schematicvault.core.net.LocalCopyPaths;
import com.einsteinmods.schematicvault.core.net.RemotePaths;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Requests to the API, all through {@link RemotePaths} (an address that is not a safe {@code v1/}
 * path, or one on a host other than the API's shards, fails without a request). One client per
 * Schematic Vault session, reading the online API ({@link HttpSource}) or a local copy of it
 * ({@link LocalFolderSource}), on a shared pool of two daemon threads that stop after a few idle
 * seconds, so nothing runs while the vault is closed.
 */
public final class ApiClient implements AutoCloseable {
  private static final int THREADS = 2;
  private static final long IDLE_SECONDS = 10;
  private static final String THREAD_NAME = "Schematic Vault I/O ";

  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
  private static final ThreadPoolExecutor EXECUTOR = createExecutor();

  private final ResourceSource source;

  /** The shards' {@code https://host/} from the last manifest; none until one is read. */
  private volatile List<String> trustedRoots = List.of();

  /**
   * The online API.
   *
   * @param baseUrl checked API base URL (http or https, ending in {@code /})
   * @param userAgent sent with every request, e.g. {@code SchematicVault/0.1.0}
   */
  public ApiClient(String baseUrl, String userAgent) {
    this(new HttpSource(baseUrl, userAgent));
  }

  private ApiClient(ResourceSource source) {
    this.source = source;
  }

  /** A local copy of the API in {@code realRoot} (a real path): nothing goes to the network. */
  public static ApiClient localCopy(Path realRoot) {
    return new ApiClient(new LocalFolderSource(realRoot));
  }

  /**
   * The catalog cache's address for this client: the base URL, or a local copy's own key ({@link
   * LocalCopyPaths#cacheKey}).
   *
   * @param baseUrl the online API's base URL
   */
  public String cacheKey(String baseUrl) {
    return source instanceof LocalFolderSource local
        ? LocalCopyPaths.cacheKey(local.root())
        : baseUrl;
  }

  /** Whether this client reads a local copy (its files are already on disk). */
  public boolean isLocalCopy() {
    return source instanceof LocalFolderSource;
  }

  /** Lets requests reach {@code roots}, the API's shards in its manifest. */
  public void trustShards(List<String> roots) {
    trustedRoots = List.copyOf(roots);
  }

  /** The I/O pool, also for disk work (cache reads and writes, hashing); never block it on I/O. */
  public static Executor executor() {
    return EXECUTOR;
  }

  /**
   * The body of {@code path} (an API path or a shard's address), decoded; fails with {@link
   * FailureKind#SERVER} for a missing file (HTTP 404 or not in the local copy: {@link
   * ApiException#isNotFound}), another status, or past {@code maxBytes} (compressed or not). No
   * thread waits for the network.
   */
  public CompletableFuture<byte[]> fetch(String path, long maxBytes) {
    return source.fetch(path, trustedRoots, maxBytes);
  }

  /** Lets requests in flight finish (a download keeps saving after the screen closes). */
  @Override
  public void close() {
    source.close();
  }

  private static ThreadPoolExecutor createExecutor() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            THREADS,
            THREADS,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
              Thread thread = new Thread(task, THREAD_NAME + THREAD_COUNTER.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
