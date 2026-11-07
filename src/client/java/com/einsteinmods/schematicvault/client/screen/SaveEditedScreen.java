package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.core.files.SafeFileNames;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Asks the name of an edited design's file, prefilled with "{@code <title> (edited)}" in the game
 * language, and shows the format it is saved in. Save hands the name over and returns; the name is
 * made safe when the file is written ({@link SafeFileNames}), never overwriting another file.
 */
final class SaveEditedScreen extends Screen {
  private static final int FIELD_WIDTH = 240;
  private static final int BUTTON_WIDTH = 116;
  private static final int GAP = 4;
  private static final int LINE_GAP = 12;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int LABEL_COLOR = 0xFFA0A0A0;

  private static final Component NAME = Component.translatable("schematicvault.save.name");
  private static final Component SAVE = Component.translatable("schematicvault.save.save");

  private final Screen parent;
  private final String initialName;
  private final Component format;
  private final Consumer<String> onSave;

  /** Kept across a resize, so the typed name survives {@link #init} again. */
  private @Nullable EditBox nameBox;

  private @Nullable Button saveButton;

  /**
   * @param initialName the name shown first (already within {@link SafeFileNames#MAX_BASE_LENGTH})
   * @param format the saved format, as shown ("Format: Litematica (.litematic)")
   * @param onSave receives the typed name
   */
  SaveEditedScreen(Screen parent, String initialName, Component format, Consumer<String> onSave) {
    super(Component.translatable("schematicvault.save.title"));
    this.parent = parent;
    this.initialName = initialName;
    this.format = format;
    this.onSave = onSave;
  }

  /** "<title> (edited)" in the game language, the title cut so the whole name stays safe. */
  static String editedName(String title) {
    String suffix = Component.translatable("schematicvault.save.edited_name", "").getString();
    String shown = title;
    int room =
        Math.max(1, SafeFileNames.MAX_BASE_LENGTH - suffix.codePointCount(0, suffix.length()));
    if (shown.codePointCount(0, shown.length()) > room) {
      shown = shown.substring(0, shown.offsetByCodePoints(0, room)).strip();
    }
    return Component.translatable("schematicvault.save.edited_name", shown).getString();
  }

  /** "Format: Litematica (.litematic)". */
  static Component formatLine(SchematicFormat format) {
    return Component.translatable("schematicvault.save.format", format.label(), format.extension());
  }

  @Override
  protected void init() {
    int fieldWidth = Math.min(FIELD_WIDTH, width - GAP * 4);
    int left = (width - fieldWidth) / 2;
    int top = height / 2 - Button.DEFAULT_HEIGHT;
    EditBox box = new EditBox(font, left, top, fieldWidth, Button.DEFAULT_HEIGHT, nameBox, NAME);
    box.setMaxLength(SafeFileNames.MAX_BASE_LENGTH);
    if (nameBox == null) {
      box.setValue(initialName);
    }
    nameBox = addRenderableWidget(box);
    int buttonsTop = top + Button.DEFAULT_HEIGHT + LINE_GAP + font.lineHeight + GAP;
    Button save =
        addRenderableWidget(
            Button.builder(SAVE, button -> save())
                .bounds(
                    width / 2 - BUTTON_WIDTH - GAP / 2,
                    buttonsTop,
                    BUTTON_WIDTH,
                    Button.DEFAULT_HEIGHT)
                .build());
    saveButton = save;
    addRenderableWidget(
        Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
            .bounds(width / 2 + GAP / 2, buttonsTop, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
            .build());
    box.setResponder(value -> save.active = !value.isBlank());
    save.active = !box.getValue().isBlank();
    setInitialFocus(box);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    Button save = saveButton;
    if (event.isConfirmation() && save != null && save.active) {
      save();
      return true;
    }
    return super.keyPressed(event);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    int top = height / 2 - Button.DEFAULT_HEIGHT;
    graphics.centeredText(font, title, width / 2, top - LINE_GAP * 2 - font.lineHeight, TEXT_COLOR);
    EditBox box = nameBox;
    if (box != null) {
      graphics.text(font, NAME, box.getX(), top - font.lineHeight - GAP, LABEL_COLOR);
    }
    graphics.centeredText(
        font, format, width / 2, top + Button.DEFAULT_HEIGHT + LINE_GAP / 2, LABEL_COLOR);
  }

  private void save() {
    EditBox box = nameBox;
    String name = box == null ? "" : box.getValue().strip();
    if (name.isEmpty()) {
      return;
    }
    onSave.accept(name);
    onClose();
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }
}
