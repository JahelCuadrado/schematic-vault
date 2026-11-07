package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.cache.ThumbnailCache;
import com.einsteinmods.schematicvault.client.preview.PreviewCamera;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The preview slot at the top of the detail panel: the design in 3D once it is loaded, else its
 * thumbnail with what the preview is doing over it (loading, failed). Left-drag turns the design,
 * right-drag pans, the wheel zooms, a double-click goes back to the first framing. Created again
 * with the panel (never per frame); the camera and the scene live in the session's {@link
 * PreviewController}, so a rebuild keeps the view and the vault and {@link PreviewScreen} show the
 * same one. Built with its corner button by {@link #withCornerButton}.
 *
 * <p>Cost per frame: a ready preview adds one reused picture-in-picture state (the renderer redraws
 * only after the camera moved); otherwise one thumbnail blit and at most one line of text.
 */
final class PreviewWidget extends AbstractWidget {
  private static final Component LOADING = Component.translatable("schematicvault.preview.loading");
  private static final Component FAILED_DOWNLOAD =
      Component.translatable("schematicvault.preview.failed.download");
  private static final Component FAILED_READ =
      Component.translatable("schematicvault.preview.failed.read");
  private static final Component TOO_LARGE =
      Component.translatable("schematicvault.preview.too_large");

  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int BAND_COLOR = 0xB0000000;
  private static final int BAND_PADDING = 2;

  /** The corner button's side and its gap to the preview's edges, in GUI pixels. */
  private static final int CORNER_SIZE = 16;

  private static final int CORNER_PADDING = 3;

  private final Font font;
  private final Backdrop backdrop;
  private final PreviewController previews;

  /** A right-drag started on the preview: vanilla only forwards left drags, the screen these. */
  private boolean panning;

  /** The button over the top-right corner, if shown: the preview ignores the pointer over it. */
  private @Nullable AbstractWidget cornerButton;

  PreviewWidget(
      Font font,
      int width,
      int height,
      Backdrop backdrop,
      Component name,
      PreviewController previews) {
    super(0, 0, width, height, name);
    this.font = font;
    this.backdrop = backdrop;
    this.previews = previews;
  }

  /** What stands in a square of the slot until the 3D is ready. */
  @FunctionalInterface
  interface Backdrop {
    void draw(GuiGraphicsExtractor graphics, int x, int y, int size);
  }

  /** The design's thumbnail; a local file has none. */
  static Backdrop thumbnail(ThumbnailCache thumbnails, @Nullable String url) {
    return (graphics, x, y, size) -> Thumbnails.draw(graphics, thumbnails, url, x, y, size);
  }

  /**
   * The preview with, once its 3D is shown, a small button over its top-right corner showing {@code
   * symbol} and named {@code name} (tooltip and narration). Over the button the pointer belongs to
   * it alone, so a click there never turns the design and a drag that starts there moves nothing.
   */
  static Layout withCornerButton(
      PreviewWidget preview, Component symbol, Component name, Runnable onPress) {
    FrameLayout frame = new FrameLayout();
    frame.addChild(preview);
    if (preview.ready()) {
      Button button =
          Button.builder(symbol, pressed -> onPress.run())
              .size(CORNER_SIZE, CORNER_SIZE)
              .tooltip(Tooltip.create(name, null))
              .createNarration(unused -> AbstractWidget.wrapDefaultNarrationMessage(name))
              .build();
      preview.cornerButton =
          frame.addChild(
              button,
              settings ->
                  settings.alignHorizontallyRight().alignVerticallyTop().padding(CORNER_PADDING));
    }
    return frame;
  }

  private boolean ready() {
    return previews.status() == PreviewController.Status.READY;
  }

  @Override
  protected void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    if (ready()) {
      previews
          .renderState()
          .place(getX(), getY(), getRight(), getBottom(), graphics.scissorStack.peek());
      graphics.guiRenderState.addPicturesInPictureState(previews.renderState());
      return;
    }
    int size = Math.min(width, height);
    backdrop.draw(graphics, getX() + (width - size) / 2, getY() + (height - size) / 2, size);
    Component message = message(previews.status());
    if (message != null) {
      int bandTop = getBottom() - font.lineHeight - BAND_PADDING * 2;
      graphics.fill(getX(), bandTop, getRight(), getBottom(), BAND_COLOR);
      graphics.centeredText(
          font, message, getX() + width / 2, bandTop + BAND_PADDING + 1, TEXT_COLOR);
    }
  }

  private static @Nullable Component message(PreviewController.Status status) {
    return switch (status) {
      case LOADING -> LOADING;
      case FAILED_DOWNLOAD -> FAILED_DOWNLOAD;
      case FAILED_READ -> FAILED_READ;
      case TOO_LARGE -> TOO_LARGE;
      case NONE, OVER_LIMIT, READY -> null;
    };
  }

  @Override
  protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
    int button = buttonInfo.button();
    return button == InputConstants.MOUSE_BUTTON_LEFT
        || button == InputConstants.MOUSE_BUTTON_RIGHT;
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    if (!ready()) {
      return;
    }
    int button = event.buttonInfo().button();
    if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
      panning = true;
    } else if (doubleClick) {
      previews.camera().reset();
    }
  }

  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    AbstractWidget corner = cornerButton;
    return super.isMouseOver(mouseX, mouseY)
        && (corner == null || !corner.isMouseOver(mouseX, mouseY));
  }

  /**
   * A right-drag the hosting screen received: vanilla forwards only left drags to the focused
   * widget ({@code ContainerEventHandler#mouseDragged}). Pans when the drag started here.
   *
   * @return whether it was this preview's drag
   */
  boolean forwardRightDrag(MouseButtonEvent event, double dx, double dy) {
    return panning
        && event.button() == InputConstants.MOUSE_BUTTON_RIGHT
        && mouseDragged(event, dx, dy);
  }

  /** The release of a right-drag that started here ends it; returns whether it was one. */
  boolean forwardRightRelease(MouseButtonEvent event) {
    if (!panning || event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
      return false;
    }
    panning = false;
    return true;
  }

  @Override
  protected void onDrag(MouseButtonEvent event, double dx, double dy) {
    if (!ready()) {
      return;
    }
    PreviewCamera camera = previews.camera();
    if (event.buttonInfo().button() == InputConstants.MOUSE_BUTTON_LEFT) {
      camera.rotate(dx, dy);
    } else {
      camera.pan(dx, dy);
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!ready() || !isMouseOver(mouseX, mouseY) || scrollY == 0) {
      return false;
    }
    previews.camera().zoom(scrollY);
    return true;
  }

  /** Clicking a picture makes no sound. */
  @Override
  public void playDownSound(SoundManager soundManager) {}

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    // A picture: the title below it is narrated.
  }
}
