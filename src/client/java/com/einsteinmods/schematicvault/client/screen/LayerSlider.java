package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

/**
 * The full-screen preview's vertical layer slider, with two handles: the upper one removes layers
 * from the top, the lower one from the bottom. They never cross; when they meet they sit one on the
 * other, both grabbable, and a single layer shows. Dragging cuts the 3D at once ({@link
 * PreviewController#showLayers}, one run of the mesh); the release, or each arrow key step, asks
 * for the solid cut ({@link PreviewController#settleLayers}) and tells the screen to refresh the
 * material list. Drawn with vanilla's slider sprites ({@code AbstractSliderButton}), turned
 * upright.
 *
 * <p>Keys: up and down move one handle by a layer: the last one grabbed. Tab reaches the upper
 * handle first, then the lower one, then the next widget (backwards with shift).
 *
 * <p>Cost per frame: three sprite blits. The label is made again only when the layers change.
 */
final class LayerSlider extends AbstractWidget {
  private static final Identifier TRACK = Identifier.withDefaultNamespace("widget/slider");
  private static final Identifier TRACK_FOCUSED =
      Identifier.withDefaultNamespace("widget/slider_highlighted");
  private static final Identifier HANDLE = Identifier.withDefaultNamespace("widget/slider_handle");
  private static final Identifier HANDLE_HOVERED =
      Identifier.withDefaultNamespace("widget/slider_handle_highlighted");

  /** Width of the slider column, in GUI pixels. */
  static final int WIDTH = 12;

  /** The handle's height (vanilla's handle is 8 wide on a horizontal slider). */
  private static final int HANDLE_HEIGHT = 8;

  private enum Handle {
    /** The top layer shown. */
    UPPER,
    /** The bottom layer shown. */
    LOWER
  }

  private final PreviewController previews;
  private final int layerCount;
  private final Runnable onChange;
  private final Runnable onSettled;

  /** The layers shown, read by each frame's handles. */
  private int bottomLayer;

  private int topLayer;

  /** The handle the pointer holds, or the arrow keys move. */
  private Handle grabbed = Handle.UPPER;

  /**
   * @param layerCount the design's Y layers (at least 2)
   * @param onChange called after each change of the layers shown (the screen remakes its label)
   * @param onSettled called when the layers rest (the screen refreshes the material list)
   */
  LayerSlider(
      int height,
      PreviewController previews,
      int layerCount,
      Runnable onChange,
      Runnable onSettled) {
    super(0, 0, WIDTH, height, label(previews.layerRange(), layerCount));
    this.previews = previews;
    this.layerCount = layerCount;
    this.onChange = onChange;
    this.onSettled = onSettled;
    LayerRange shown = previews.layerRange();
    this.bottomLayer = shown.bottom();
    this.topLayer = shown.top();
  }

  /** "Layers 5-12 / 40", or "Layer 7 / 40" when one shows; layers counted from 1. */
  static Component label(LayerRange shown, int layerCount) {
    return shown.isSingleLayer()
        ? Component.translatable("schematicvault.preview.layer", shown.top() + 1, layerCount)
        : Component.translatable(
            "schematicvault.preview.layer_range", shown.bottom() + 1, shown.top() + 1, layerCount);
  }

  @Override
  protected void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    int white = ARGB.white(alpha);
    graphics.blitSprite(
        RenderPipelines.GUI_TEXTURED,
        isFocused() ? TRACK_FOCUSED : TRACK,
        getX(),
        getY(),
        width,
        height,
        white);
    Handle highlighted = isFocused() ? grabbed : isHovered() ? nearest(mouseY) : null;
    extractHandle(graphics, Handle.UPPER, highlighted, white);
    extractHandle(graphics, Handle.LOWER, highlighted, white);
  }

  private void extractHandle(
      GuiGraphicsExtractor graphics, Handle handle, @Nullable Handle highlighted, int color) {
    graphics.blitSprite(
        RenderPipelines.GUI_TEXTURED,
        handle == highlighted ? HANDLE_HOVERED : HANDLE,
        getX(),
        handleTop(handle),
        width,
        HANDLE_HEIGHT,
        color);
  }

  /** Pixels each handle travels: both fit at once, so they never hide each other. */
  private int travel() {
    return Math.max(1, height - HANDLE_HEIGHT * 2);
  }

  /** The upper handle at the top for the top layer; the lower one under it when they meet. */
  private int handleTop(Handle handle) {
    int highest = layerCount - 1;
    return handle == Handle.UPPER
        ? getY() + (int) ((long) travel() * (highest - topLayer) / highest)
        : getY() + HANDLE_HEIGHT + (int) ((long) travel() * (highest - bottomLayer) / highest);
  }

  private Handle nearest(double mouseY) {
    double toUpper = Math.abs(mouseY - handleTop(Handle.UPPER) - HANDLE_HEIGHT / 2.0);
    double toLower = Math.abs(mouseY - handleTop(Handle.LOWER) - HANDLE_HEIGHT / 2.0);
    return toUpper <= toLower ? Handle.UPPER : Handle.LOWER;
  }

  @Override
  protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
    return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT;
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    grabbed = nearest(event.y());
    moveTo(event.y());
  }

  @Override
  protected void onDrag(MouseButtonEvent event, double dx, double dy) {
    moveTo(event.y());
  }

  @Override
  public void onRelease(MouseButtonEvent event) {
    settle();
  }

  /** Arrow up or down: the grabbed handle one layer up or down, made solid at once. */
  @Override
  public boolean keyPressed(KeyEvent event) {
    int step = event.isUp() ? 1 : event.isDown() ? -1 : 0;
    if (step == 0) {
      return false;
    }
    move((grabbed == Handle.UPPER ? topLayer : bottomLayer) + step);
    settle();
    return true;
  }

  /** Tab stops at each handle in turn before leaving the slider. */
  @Override
  public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
    if (event instanceof FocusNavigationEvent.TabNavigation tab) {
      Handle first = tab.forward() ? Handle.UPPER : Handle.LOWER;
      Handle last = tab.forward() ? Handle.LOWER : Handle.UPPER;
      if (!isFocused()) {
        grabbed = first;
      } else if (grabbed == first) {
        grabbed = last;
        return ComponentPath.leaf(this);
      }
    }
    return super.nextFocusPath(event);
  }

  private void moveTo(double mouseY) {
    int centre = grabbed == Handle.UPPER ? HANDLE_HEIGHT / 2 : HANDLE_HEIGHT + HANDLE_HEIGHT / 2;
    double fromTop = (mouseY - getY() - centre) / travel();
    int highest = layerCount - 1;
    move(highest - (int) Math.round(Math.clamp(fromTop, 0.0, 1.0) * highest));
  }

  /** Puts the grabbed handle on {@code layer}, stopped by the design and the other handle. */
  private void move(int layer) {
    int bottom = grabbed == Handle.LOWER ? Math.clamp(layer, 0, topLayer) : bottomLayer;
    int top = grabbed == Handle.UPPER ? Math.clamp(layer, bottomLayer, layerCount - 1) : topLayer;
    if (bottom == bottomLayer && top == topLayer) {
      return;
    }
    bottomLayer = bottom;
    topLayer = top;
    LayerRange shown = new LayerRange(bottom, top);
    previews.showLayers(shown);
    setMessage(label(shown, layerCount));
    onChange.run();
  }

  private void settle() {
    previews.settleLayers();
    onSettled.run();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, getMessage());
  }
}
