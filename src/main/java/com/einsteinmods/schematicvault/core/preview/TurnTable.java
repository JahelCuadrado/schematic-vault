package com.einsteinmods.schematicvault.core.preview;

/**
 * The slow turn of a preview nobody is steering: how far it turns on each frame, and how much of an
 * area the design needs to stay inside it through the whole turn. The turn is measured in time, so
 * it runs at the same speed at any frame rate, and it steps at most {@value #MAX_STEPS_PER_SECOND}
 * times a second: each step redraws the whole mesh, and at this speed a higher rate shows nothing
 * more. Render thread.
 */
public final class TurnTable {
  /** One whole turn in 18 seconds. */
  public static final float DEGREES_PER_SECOND = 20.0F;

  private static final int MAX_STEPS_PER_SECOND = 30;
  private static final float MILLIS_PER_SECOND = 1000.0F;
  private static final long STEP_MILLIS = (long) MILLIS_PER_SECOND / MAX_STEPS_PER_SECOND;

  /** Longest step: after a pause (the big view was open) the turn goes on, it never jumps. */
  private static final long MAX_STEP_MILLIS = 200;

  private static final long NEVER = -1;

  private long lastMillis = NEVER;

  /**
   * The degrees to turn at {@code nowMillis}, a monotonic clock; 0 when it is too soon for a step
   * (or on the first call), and then the view has not moved.
   */
  public float advance(long nowMillis) {
    if (lastMillis == NEVER) {
      lastMillis = nowMillis;
      return 0.0F;
    }
    long elapsed = nowMillis - lastMillis;
    if (elapsed < STEP_MILLIS) {
      return 0.0F;
    }
    lastMillis = nowMillis;
    return Math.min(elapsed, MAX_STEP_MILLIS) * DEGREES_PER_SECOND / MILLIS_PER_SECOND;
  }

  /** The widest the box of a design ever looks from the side while it turns around Y. */
  public static float widthSpan(float sizeX, float sizeZ) {
    return (float) Math.hypot(sizeX, sizeZ);
  }

  /** The tallest the box ever looks while it turns around Y, seen from {@code pitchDegrees}. */
  public static float heightSpan(float sizeX, float sizeY, float sizeZ, float pitchDegrees) {
    double pitch = Math.toRadians(pitchDegrees);
    return (float)
        (sizeY * Math.abs(Math.cos(pitch)) + widthSpan(sizeX, sizeZ) * Math.abs(Math.sin(pitch)));
  }
}
