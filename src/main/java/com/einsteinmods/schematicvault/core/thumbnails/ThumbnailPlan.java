package com.einsteinmods.schematicvault.core.thumbnails;

/**
 * What generating a folder's thumbnails will do, told to the player before it starts.
 *
 * @param files the files that will be read
 * @param bytes their total size
 * @param skipped the files left out unread, over {@link #MAX_FILE_BYTES}
 */
public record ThumbnailPlan(int files, long bytes, int skipped) {
  /**
   * Past this a file is not read: blocks cannot be told from a file's size (of the API's 6,312
   * designs the 500,000-block limit is crossed from 8 KB upward), so this is only a guard against
   * the rare file whose read alone is the cost. One design passes it: a 10.5 MB structure that
   * inflates to 132 MB of block compounds. The block limit decides for all the others, once read.
   */
  public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

  /**
   * Time of a typical file with the pacing's floor at 60 FPS: 0.1 s between images, plus reading,
   * meshing and the PNG (the median design is 5 KB).
   */
  private static final double SECONDS_PER_FILE = 0.15;

  /** Read and mesh of big files, which dominates them: a low figure, an estimate may run short. */
  private static final double BYTES_PER_SECOND = 1024.0 * 1024.0;

  private static final int SECONDS_STEP = 5;
  private static final int SECONDS_PER_MINUTE = 60;

  /** The plan for files of the given {@code sizes}. */
  public static ThumbnailPlan of(long[] sizes) {
    int files = 0;
    int skipped = 0;
    long bytes = 0;
    for (long size : sizes) {
      if (tooBig(size)) {
        skipped++;
      } else {
        files++;
        bytes += size;
      }
    }
    return new ThumbnailPlan(files, bytes, skipped);
  }

  /** Whether a file of {@code size} bytes is left out unread. */
  public static boolean tooBig(long size) {
    return size > MAX_FILE_BYTES;
  }

  /**
   * The expected seconds, rounded up: to 5 seconds under a minute, to whole minutes from there. A
   * figure to set expectations, not a measure.
   */
  public int estimatedSeconds() {
    double seconds = files * SECONDS_PER_FILE + bytes / BYTES_PER_SECOND;
    int step = seconds < SECONDS_PER_MINUTE ? SECONDS_STEP : SECONDS_PER_MINUTE;
    return (int) Math.ceil(seconds / step) * step;
  }
}
