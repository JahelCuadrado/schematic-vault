package com.einsteinmods.schematicvault.client.preview;

import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Asks {@link PreviewRenderer} to draw a scene into the preview's area this frame. One instance per
 * preview, updated in place by the widget each frame it is shown (vanilla's own states are new
 * records per frame): its bounds are only recomputed when the area or the clip changed.
 */
public final class PreviewRenderState implements PictureInPictureRenderState {
  private final PreviewCamera camera;

  /** Who wants this scene as a thumbnail, off screen; null for a preview on screen. */
  private final @Nullable ThumbnailGenerator capture;

  private @Nullable PreviewScene scene;
  private int x0;
  private int y0;
  private int x1;
  private int y1;
  private @Nullable ScreenRectangle scissorArea;
  private @Nullable ScreenRectangle bounds;

  PreviewRenderState(PreviewCamera camera) {
    this(camera, null);
  }

  PreviewRenderState(PreviewCamera camera, @Nullable ThumbnailGenerator capture) {
    this.camera = camera;
    this.capture = capture;
  }

  @Nullable ThumbnailGenerator capture() {
    return capture;
  }

  void scene(@Nullable PreviewScene shown) {
    scene = shown;
  }

  @Nullable PreviewScene scene() {
    return scene;
  }

  PreviewCamera camera() {
    return camera;
  }

  /** Sets the area to draw in and the clip in force; no allocation unless one of them changed. */
  public void place(int left, int top, int right, int bottom, @Nullable ScreenRectangle scissor) {
    if (left == x0
        && top == y0
        && right == x1
        && bottom == y1
        && Objects.equals(scissor, scissorArea)
        && bounds != null) {
      return;
    }
    x0 = left;
    y0 = top;
    x1 = right;
    y1 = bottom;
    scissorArea = scissor;
    bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor);
  }

  @Override
  public int x0() {
    return x0;
  }

  @Override
  public int x1() {
    return x1;
  }

  @Override
  public int y0() {
    return y0;
  }

  @Override
  public int y1() {
    return y1;
  }

  @Override
  public float scale() {
    return 1.0F;
  }

  @Override
  public @Nullable ScreenRectangle scissorArea() {
    return scissorArea;
  }

  @Override
  public @Nullable ScreenRectangle bounds() {
    return bounds;
  }
}
