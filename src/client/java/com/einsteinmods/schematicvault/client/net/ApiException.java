package com.einsteinmods.schematicvault.client.net;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** A failed request or download with its {@link FailureKind}; the message is for the log only. */
public final class ApiException extends RuntimeException {
  private final FailureKind kind;
  private final boolean notFound;

  public ApiException(FailureKind kind, String message) {
    super(message);
    this.kind = kind;
    this.notFound = false;
  }

  public ApiException(FailureKind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.notFound = false;
  }

  private ApiException(String message) {
    super(message);
    this.kind = FailureKind.SERVER;
    this.notFound = true;
  }

  /** The server has no such path (HTTP 404): a server failure that {@link #isNotFound} names. */
  public static ApiException notFound(String message) {
    return new ApiException(message);
  }

  public FailureKind kind() {
    return kind;
  }

  /**
   * The kind of any failure a future ends with: its own for an {@code ApiException}, network for
   * other I/O errors, server for anything else (bad data).
   */
  public static FailureKind kindOf(Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause instanceof ApiException api) {
      return api.kind;
    }
    return cause instanceof IOException ? FailureKind.NETWORK : FailureKind.SERVER;
  }

  /** Whether a future ended because the server has no such path (HTTP 404). */
  public static boolean isNotFound(Throwable failure) {
    return unwrap(failure) instanceof ApiException api && api.notFound;
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }
}
