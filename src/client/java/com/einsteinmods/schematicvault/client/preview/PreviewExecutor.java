package com.einsteinmods.schematicvault.client.preview;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The preview's own worker: one low-priority daemon thread that reads design files and builds
 * meshes, never the render thread nor the API's I/O pool (whose two threads also deliver HTTP
 * bodies). It stops after a few idle seconds, so nothing runs while the vault is closed.
 */
final class PreviewExecutor {
  private static final long IDLE_SECONDS = 10;
  private static final String THREAD_NAME = "Schematic Vault preview";
  private static final ThreadPoolExecutor EXECUTOR = create();

  private PreviewExecutor() {}

  static Executor executor() {
    return EXECUTOR;
  }

  private static ThreadPoolExecutor create() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
              Thread thread = new Thread(task, THREAD_NAME);
              thread.setDaemon(true);
              // The game's own threads come first: a preview may take a moment longer.
              thread.setPriority(Thread.MIN_PRIORITY);
              return thread;
            });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
