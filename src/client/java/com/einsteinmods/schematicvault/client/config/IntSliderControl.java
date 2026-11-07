package com.einsteinmods.schematicvault.client.config;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Whole-number row in the style of YACL's slider controller: shows the value (with digit grouping),
 * and while hovered or focused a thin track with a thumb appears on the right. Dragging or clicking
 * the track picks a value on the slider's steps; the arrow keys move one step.
 */
final class IntSliderControl extends OptionControl<Integer> {
  private static final int THUMB_WIDTH = 4;
  private static final int TRACK_HEIGHT = 10;
  private static final int VALUE_GAP = 6;

  /** The track takes this fraction of the row width. */
  private static final int TRACK_WIDTH_DIVISOR = 3;

  private static final String VALUE_FORMAT = "%,d";

  private final Component description;
  private final int min;
  private final int max;
  private final int step;
  private final IntUnaryOperator snap;
  private boolean dragging;

  /** The text of {@link #shownValue}, made again only when the value changes. */
  private Component shownText;

  private int shownValue;

  IntSliderControl(
      Font font,
      Component name,
      Component description,
      int min,
      int max,
      int step,
      IntUnaryOperator snap,
      int initial,
      int defaultValue,
      Consumer<Integer> onPlayerChange) {
    super(font, name, initial, defaultValue, onPlayerChange);
    this.description = description;
    this.min = min;
    this.max = max;
    this.step = step;
    this.snap = snap;
    this.shownValue = initial;
    this.shownText = format(initial);
  }

  @Override
  public Component description() {
    return description;
  }

  @Override
  protected Component valueText() {
    int value = get();
    if (value != shownValue) {
      shownValue = value;
      shownText = format(value);
    }
    return shownText;
  }

  private static Component format(int value) {
    return Component.literal(String.format(Locale.ROOT, VALUE_FORMAT, value));
  }

  @Override
  protected int controlWidth() {
    int valueWidth = super.controlWidth();
    return showsTrack() ? trackWidth() + VALUE_GAP + THUMB_WIDTH / 2 + valueWidth : valueWidth;
  }

  @Override
  protected void extractControl(GuiGraphicsExtractor graphics, int textY) {
    if (!showsTrack()) {
      super.extractControl(graphics, textY);
      return;
    }
    int left = trackLeft();
    int right = trackRight();
    int middle = centerY();
    graphics.fill(left, middle - 1, right, middle, TEXT_COLOR);
    graphics.fill(left + 1, middle, right + 1, middle + 1, CONTROL_SHADOW_COLOR);
    int thumbX = left + (int) Math.round((right - left) * fraction());
    int top = middle - TRACK_HEIGHT / 2;
    int bottom = top + TRACK_HEIGHT;
    int halfThumb = THUMB_WIDTH / 2;
    graphics.fill(
        thumbX - halfThumb + 1, top + 1, thumbX + halfThumb + 1, bottom + 1, CONTROL_SHADOW_COLOR);
    graphics.fill(thumbX - halfThumb, top, thumbX + halfThumb, bottom, TEXT_COLOR);
    Component text = valueText();
    graphics.text(
        font, text, left - halfThumb - VALUE_GAP - font.width(text), textY, TEXT_COLOR, true);
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    dragging = isOverTrack(event.x());
    if (dragging) {
      setFromMouse(event.x());
    }
  }

  @Override
  protected void onDrag(MouseButtonEvent event, double dx, double dy) {
    if (dragging) {
      setFromMouse(event.x());
    }
  }

  @Override
  public void onRelease(MouseButtonEvent event) {
    dragging = false;
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.isLeft() || event.isRight()) {
      setByPlayer(snap.applyAsInt(get() + (event.isLeft() ? -step : step)));
      return true;
    }
    return false;
  }

  /** Only while it can be used: a disabled row just shows its value. */
  private boolean showsTrack() {
    return active && (isHoveredOrFocused() || dragging);
  }

  private int trackWidth() {
    return getWidth() / TRACK_WIDTH_DIVISOR;
  }

  private int trackRight() {
    return getRight() - X_PADDING - THUMB_WIDTH / 2;
  }

  private int trackLeft() {
    return trackRight() - trackWidth();
  }

  private boolean isOverTrack(double mouseX) {
    return mouseX >= trackLeft() - THUMB_WIDTH && mouseX <= trackRight() + THUMB_WIDTH;
  }

  private double fraction() {
    return Mth.clamp((double) (get() - min) / (max - min), 0.0, 1.0);
  }

  private void setFromMouse(double mouseX) {
    double ratio = Mth.clamp((mouseX - trackLeft()) / (trackRight() - trackLeft()), 0.0, 1.0);
    setByPlayer(snap.applyAsInt((int) Math.round(min + ratio * (max - min))));
  }
}
