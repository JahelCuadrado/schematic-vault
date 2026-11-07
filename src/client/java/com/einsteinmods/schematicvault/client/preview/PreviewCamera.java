package com.einsteinmods.schematicvault.client.preview;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the preview looks from: a turn around the design ({@code yaw}, a game yaw: 180 is north,
 * 225 north-east), a tilt ({@code pitch}, positive looks down), a zoom over the fitted framing and
 * a pan in GUI pixels. Starts from the north-east, 30° above, the whole design fitted (the way the
 * inventory shows a block). Each change bumps {@link #version}, so the renderer redraws only when
 * the view moved. Render thread.
 */
public final class PreviewCamera {
  public static final float DEFAULT_YAW = 225.0F;
  public static final float DEFAULT_PITCH = 30.0F;

  /** Just short of straight down or up: the view never flips over. */
  private static final float MAX_PITCH = 89.0F;

  private static final float FULL_TURN = 360.0F;
  private static final float DEGREES_PER_PIXEL = 0.6F;
  private static final float MIN_ZOOM = 0.25F;
  private static final float MAX_ZOOM = 32.0F;

  /** Zoom factor of one wheel notch. */
  private static final float ZOOM_STEP = 1.15F;

  private float yaw = DEFAULT_YAW;
  private float pitch = DEFAULT_PITCH;
  private float zoom = 1.0F;
  private float panX;
  private float panY;
  private int version;

  /** Framed for the whole turn: {@link #fitsWholeTurn}. */
  private boolean wholeTurn;

  public float yaw() {
    return yaw;
  }

  public float pitch() {
    return pitch;
  }

  /** Scale over the fitted framing. */
  public float zoom() {
    return zoom;
  }

  /** Pan in GUI pixels, right and down. */
  public float panX() {
    return panX;
  }

  public float panY() {
    return panY;
  }

  /** Bumped on every change. */
  public int version() {
    return version;
  }

  /** Turns the design under a drag of ({@code dx}, {@code dy}) GUI pixels. */
  public void rotate(double dx, double dy) {
    yaw = (float) ((yaw - dx * DEGREES_PER_PIXEL) % 360.0);
    pitch = Math.clamp((float) (pitch + dy * DEGREES_PER_PIXEL), -MAX_PITCH, MAX_PITCH);
    version++;
  }

  /** Turns the design by {@code degrees} around its vertical axis, for the slow turn. */
  public void spin(float degrees) {
    yaw = (yaw + degrees) % FULL_TURN;
    version++;
  }

  /** Whether the design is framed to stay inside the area at any yaw, not just the first one. */
  public boolean fitsWholeTurn() {
    return wholeTurn;
  }

  public void fitWholeTurn(boolean fit) {
    if (wholeTurn != fit) {
      wholeTurn = fit;
      version++;
    }
  }

  public void pan(double dx, double dy) {
    panX += (float) dx;
    panY += (float) dy;
    version++;
  }

  /** Zooms in for a positive wheel amount, out for a negative one. */
  public void zoom(double amount) {
    zoom = Math.clamp(zoom * (float) Math.pow(ZOOM_STEP, amount), MIN_ZOOM, MAX_ZOOM);
    version++;
  }

  /** Back to the first framing. */
  public void reset() {
    yaw = DEFAULT_YAW;
    pitch = DEFAULT_PITCH;
    zoom = 1.0F;
    panX = 0.0F;
    panY = 0.0F;
    version++;
  }

  /** The view's rotation: what turns the design's axes into the screen's. */
  static Quaternionf rotation(float yawDegrees, float pitchDegrees, Quaternionf dest) {
    return dest.rotationX((float) Math.toRadians(pitchDegrees))
        .rotateY((float) Math.toRadians(yawDegrees));
  }

  /**
   * The unit direction, in the design's axes, from the design toward the viewer: a point further
   * along it is nearer.
   */
  static Vector3f towardViewer(float yawDegrees, float pitchDegrees, Vector3f dest) {
    Quaternionf rotation = rotation(yawDegrees, pitchDegrees, new Quaternionf());
    return rotation.conjugate().transform(dest.set(0.0F, 0.0F, 1.0F));
  }
}
