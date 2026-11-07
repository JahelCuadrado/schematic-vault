package com.einsteinmods.schematicvault.client.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads a whole in-memory stream (a gzip body), refusing anything bigger than a limit. */
public final class LimitedReads {
  private static final int BUFFER_SIZE = 16 * 1024;

  private LimitedReads() {}

  /**
   * @throws ApiException with {@link FailureKind#SERVER} past {@code maxBytes}
   */
  public static byte[] readAll(InputStream input, long maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(BUFFER_SIZE);
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      total += read;
      if (total > maxBytes) {
        throw new ApiException(FailureKind.SERVER, "response over " + maxBytes + " bytes");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }
}
