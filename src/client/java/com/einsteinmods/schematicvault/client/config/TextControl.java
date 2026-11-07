package com.einsteinmods.schematicvault.client.config;

import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Option row whose value is free text, edited in a vanilla {@link EditBox} on the right. Text the
 * validity check rejects turns red and adds the reason to the description; the screen does not save
 * while a row is invalid.
 */
final class TextControl extends OptionControl<String> {
  private static final int MAX_LENGTH = 256;
  private static final int BOX_HEIGHT = 16;
  private static final int BOX_INSET = 2;

  /** {@link EditBox}'s own text color. */
  private static final int VALID_TEXT_COLOR = 0xFFE0E0E0;

  private static final int INVALID_TEXT_COLOR = 0xFFFF6060;
  private static final String PARAGRAPH_BREAK = "\n\n";

  /** Share of the row given to the text box. */
  private static final float BOX_SHARE = 0.6F;

  private final Component description;
  private final Component invalidDescription;
  private final Predicate<String> validity;
  private final EditBox box;

  /** The value {@link #valid} was checked for (by identity), so a frame never checks it again. */
  private @Nullable String checkedValue;

  private boolean valid;

  TextControl(
      Font font,
      Component name,
      Component description,
      Component invalidReason,
      Component hint,
      String initial,
      String defaultValue,
      Predicate<String> validity,
      Consumer<String> onPlayerChange) {
    super(font, name, initial, defaultValue, onPlayerChange);
    this.description = description;
    this.invalidDescription =
        description
            .copy()
            .append(PARAGRAPH_BREAK)
            .append(invalidReason.copy().withStyle(ChatFormatting.RED));
    this.validity = validity;
    this.box = new EditBox(font, 0, BOX_HEIGHT, name);
    box.setMaxLength(MAX_LENGTH);
    box.setHint(hint);
    box.setValue(initial);
    box.setResponder(this::setByPlayer);
  }

  /** Whether the current text passes the validity check. */
  boolean isValid() {
    String value = get();
    if (value != checkedValue) {
      checkedValue = value;
      valid = validity.test(value);
    }
    return valid;
  }

  @Override
  public Component description() {
    return isValid() ? description : invalidDescription;
  }

  @Override
  protected Component valueText() {
    return Component.literal(get());
  }

  @Override
  protected int controlWidth() {
    return (int) (getWidth() * BOX_SHARE);
  }

  @Override
  protected void extractControl(GuiGraphicsExtractor graphics, int textY) {
    if (!box.isFocused() && !box.getValue().equals(get())) {
      // Undo or Reset changed the value: show it.
      box.setValue(get());
    }
    box.setTextColor(isValid() ? VALID_TEXT_COLOR : INVALID_TEXT_COLOR);
    int width = controlWidth();
    box.setRectangle(width, BOX_HEIGHT, getRight() - width - BOX_INSET, getY() + BOX_INSET);
    box.extractRenderState(graphics, 0, 0, 0.0F);
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    box.setFocused(true);
    box.onClick(event, doubleClick);
  }

  @Override
  public void setFocused(boolean focused) {
    super.setFocused(focused);
    box.setFocused(focused);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    return box.keyPressed(event);
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    return box.charTyped(event);
  }
}
