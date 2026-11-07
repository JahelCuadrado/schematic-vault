package com.einsteinmods.schematicvault.client.cache;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.einsteinmods.schematicvault.client.VaultSession;
import com.einsteinmods.schematicvault.client.net.ApiClient;
import com.einsteinmods.schematicvault.core.config.VaultSettings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One background refresh of the catalog cache per world or server joined, while no vault is open,
 * so the next open finds the current revision on disk: the manifest, then every page only when the
 * cache's header ({@link CatalogCache#readHeader}) shows another revision or language ({@link
 * CatalogRepository#refreshCache}). Nothing stays in memory once the cache is written, and the HTTP
 * client stops. Not with a local API copy (nothing to download). A vault opened meanwhile awaits it
 * ({@link #pending}). A failure is one log line. Render thread, apart from the work on the I/O
 * pool; nothing per tick.
 */
public final class CatalogPrefetch {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogPrefetch.class);
  private static final CompletableFuture<Void> NONE = CompletableFuture.completedFuture(null);

  /** The refresh in flight; completes normally, failed or not. */
  private static final AtomicReference<CompletableFuture<Void>> IN_FLIGHT =
      new AtomicReference<>(NONE);

  /** Vault sessions open now (render thread). */
  private static int openSessions;

  private CatalogPrefetch() {}

  /** On joining a world or server (render thread): starts the refresh unless one is not needed. */
  public static void onJoin(Minecraft minecraft) {
    if (openSessions > 0 || !IN_FLIGHT.get().isDone()) {
      return;
    }
    String minecraftLanguage = minecraft.getLanguageManager().getSelected();
    CompletableFuture<Void> refresh =
        CompletableFuture.supplyAsync(CatalogPrefetch::prepare, ApiClient.executor())
            .thenCompose(
                background ->
                    background == null
                        ? NONE
                        : background
                            .repository()
                            .refreshCache(minecraftLanguage)
                            .whenComplete((ignored, failure) -> background.api().close()))
            .handle(
                (ignored, failure) -> {
                  if (failure != null) {
                    LOGGER.warn("Background catalog refresh failed: {}", failure.toString());
                  }
                  return null;
                });
    IN_FLIGHT.set(refresh);
    refresh.thenRun(() -> IN_FLIGHT.compareAndSet(refresh, NONE));
  }

  /** The background refresh in flight, or a completed future; never completes exceptionally. */
  static CompletableFuture<Void> pending() {
    return IN_FLIGHT.get();
  }

  /** A vault session opened (render thread): no refresh starts until it closes. */
  public static void sessionOpened() {
    openSessions++;
  }

  /** A vault session closed (render thread). */
  public static void sessionClosed() {
    openSessions--;
  }

  /** The settings and a client for them, or null with a local API copy (I/O pool). */
  private static @Nullable Background prepare() {
    if (VaultSession.localApiCopy(FabricLoader.getInstance().getGameDir()) != null) {
      return null;
    }
    VaultSettings settings = SchematicVaultClient.settings();
    ApiClient api = new ApiClient(settings.apiBaseUrl(), VaultSession.userAgent());
    CatalogCache cache = new CatalogCache(VaultSession.cacheDirectory(), settings.apiBaseUrl());
    return new Background(new CatalogRepository(api, cache, Runnable::run), api);
  }

  private record Background(CatalogRepository repository, ApiClient api) {}
}
