package com.einsteinmods.schematicvault.core.thumbnails;

/**
 * When the next thumbnail may be drawn on the render thread: at most one image every {@link
 * #MIN_FRAMES} frames, and never more than one part in {@link #MAX_SHARE} of the render thread's
 * time, whatever an image costs. The cheap image of a median design waits only for the frames; an
 * expensive one buys itself a longer rest, up to {@link #MAX_REST_NANOS}.
 */
public final class ThumbnailPacing {
  /** Frames between two images: 10 images a second at 60 FPS. */
  public static final int MIN_FRAMES = 6;

  /** An image may take one part in this of the time: 5 %. */
  public static final int MAX_SHARE = 20;

  /** The longest rest after an image, so a huge design does not stall the rest of the folder. */
  public static final long MAX_REST_NANOS = 2_000_000_000L;

  private int frames;
  private long earliestNanos = Long.MIN_VALUE;

  /** Called once per frame: whether an image may be drawn in this one. */
  public boolean ready(long nowNanos) {
    if (frames < MIN_FRAMES) {
      frames++;
    }
    return frames >= MIN_FRAMES && nowNanos >= earliestNanos;
  }

  /** An image was drawn at {@code nowNanos}, which cost the render thread {@code costNanos}. */
  public void drawn(long costNanos, long nowNanos) {
    frames = 0;
    earliestNanos = nowNanos + Math.min(costNanos * (MAX_SHARE - 1), MAX_REST_NANOS);
  }
}
