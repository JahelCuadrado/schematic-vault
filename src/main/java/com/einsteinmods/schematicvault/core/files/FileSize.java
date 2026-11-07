package com.einsteinmods.schematicvault.core.files;

import java.util.Locale;

/**
 * A file size as players read it: bytes below 1 KB, else KB or MB (1024-based) with one decimal
 * below 10 ({@code 4.2 KB}, {@code 125 KB}, {@code 9.8 MB}).
 *
 * @param amount the number, with a dot as decimal separator
 */
public record FileSize(String amount, Unit unit) {
  private static final long KILOBYTE = 1024;
  private static final long MEGABYTE = KILOBYTE * KILOBYTE;
  private static final double ONE_DECIMAL_BELOW = 10.0;

  public enum Unit {
    BYTES,
    KILOBYTES,
    MEGABYTES
  }

  public static FileSize of(long bytes) {
    long size = Math.max(0, bytes);
    if (size < KILOBYTE) {
      return new FileSize(Long.toString(size), Unit.BYTES);
    }
    if (size < MEGABYTE) {
      return new FileSize(amount(size / (double) KILOBYTE), Unit.KILOBYTES);
    }
    return new FileSize(amount(size / (double) MEGABYTE), Unit.MEGABYTES);
  }

  private static String amount(double value) {
    return value < ONE_DECIMAL_BELOW
        ? String.format(Locale.ROOT, "%.1f", value)
        : Long.toString(Math.round(value));
  }
}
