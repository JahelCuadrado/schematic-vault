package com.einsteinmods.schematicvault.client.net;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Collects a response body into memory without ever blocking a thread (the HTTP client delivers the
 * bytes on the shared two-thread pool: a blocking read there would wait for bytes that only that
 * pool can deliver), and cancels the response once it passes {@code maxBytes}.
 */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private static final int INITIAL_CAPACITY = 16 * 1024;

  private final long maxBytes;
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(INITIAL_CAPACITY);
  private Flow.Subscription subscription;
  private long received;

  LimitedBodySubscriber(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription newSubscription) {
    subscription = newSubscription;
    newSubscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    if (body.isDone()) {
      return;
    }
    for (ByteBuffer buffer : buffers) {
      received += buffer.remaining();
      if (received > maxBytes) {
        subscription.cancel();
        body.completeExceptionally(
            new ApiException(FailureKind.SERVER, "response over " + maxBytes + " bytes"));
        return;
      }
      // Copied: the client may reuse its buffers after this call.
      byte[] chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      bytes.writeBytes(chunk);
    }
  }

  @Override
  public void onError(Throwable failure) {
    body.completeExceptionally(failure);
  }

  @Override
  public void onComplete() {
    body.complete(bytes.toByteArray());
  }
}
