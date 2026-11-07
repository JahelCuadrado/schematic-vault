package com.einsteinmods.schematicvault.client.net;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * The online API: one {@link HttpClient} on {@link ApiClient#executor}. Asks for gzip and decodes
 * it (JSON compresses about 3:1). Bodies are collected without blocking ({@link
 * LimitedBodySubscriber}). No retries: a failure is shown and the player retries.
 */
final class HttpSource implements ResourceSource {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final int HTTP_OK = 200;
  private static final int HTTP_NOT_FOUND = 404;
  private static final String ACCEPT_ENCODING = "Accept-Encoding";
  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String USER_AGENT = "User-Agent";
  private static final String GZIP = "gzip";

  private final String baseUrl;
  private final String userAgent;
  private final HttpClient client;

  HttpSource(String baseUrl, String userAgent) {
    this.baseUrl = baseUrl;
    this.userAgent = userAgent;
    this.client =
        HttpClient.newBuilder()
            .executor(ApiClient.executor())
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  @Override
  public CompletableFuture<byte[]> fetch(String path, List<String> trustedRoots, long maxBytes) {
    URI uri = RemotePaths.resolve(baseUrl, trustedRoots, path).orElse(null);
    if (uri == null) {
      return CompletableFuture.failedFuture(
          new ApiException(FailureKind.SERVER, "unsafe API path " + path));
    }
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header(ACCEPT_ENCODING, GZIP)
            .header(USER_AGENT, userAgent)
            .GET()
            .build();
    return client
        .sendAsync(request, info -> new LimitedBodySubscriber(maxBytes))
        .thenApply(response -> decodedBody(response, path, maxBytes));
  }

  private static byte[] decodedBody(HttpResponse<byte[]> response, String path, long maxBytes) {
    if (response.statusCode() == HTTP_NOT_FOUND) {
      throw ApiException.notFound("HTTP 404 for " + path);
    }
    if (response.statusCode() != HTTP_OK) {
      throw new ApiException(FailureKind.SERVER, "HTTP " + response.statusCode() + " for " + path);
    }
    String encoding = response.headers().firstValue(CONTENT_ENCODING).orElse("");
    if (!encoding.toLowerCase(Locale.ROOT).contains(GZIP)) {
      return response.body();
    }
    try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
      return LimitedReads.readAll(input, maxBytes);
    } catch (IOException exception) {
      throw new ApiException(FailureKind.SERVER, "bad gzip body for " + path, exception);
    }
  }

  @Override
  public void close() {
    client.shutdown();
  }
}
