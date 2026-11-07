package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge.Outcome;
import com.einsteinmods.schematicvault.core.files.PlacementNames;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The "Load in Litematica" and "Place here" buttons shared by the detail panel and the full screen
 * view, and the toast that reports what Litematica did (a toast shows over any screen). Without
 * Litematica, or once its bridge turned itself off, there are no buttons.
 */
final class LitematicaButtons {
  private static final Component LOAD = Component.translatable("schematicvault.litematica.load");
  private static final Component PLACE = Component.translatable("schematicvault.litematica.place");

  /** One toast slot: a new report replaces the one on screen. */
  private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

  private LitematicaButtons() {}

  /** Whether the buttons show for {@code format}: Litematica is installed, on, and reads it. */
  static boolean shown(LitematicaBridge litematica, SchematicFormat format) {
    return litematica.available() && litematica.supports(format);
  }

  /**
   * The two buttons, Load then Place, {@code width} wide together with {@code gap} between them; no
   * button when Litematica is missing, off, or cannot read {@code format}.
   *
   * @param active false while the file is being downloaded or saved
   */
  static List<Button> create(
      LitematicaBridge litematica,
      SchematicFormat format,
      int width,
      int gap,
      boolean active,
      Consumer<LitematicaAction> onPress) {
    if (!shown(litematica, format)) {
      return List.of();
    }
    int loadWidth = Math.max(1, (width - gap) / 2);
    int placeWidth = Math.max(1, width - gap - loadWidth);
    Button load =
        Button.builder(LOAD, button -> onPress.accept(LitematicaAction.LOAD))
            .width(loadWidth)
            .build();
    Button place =
        Button.builder(PLACE, button -> onPress.accept(LitematicaAction.PLACE))
            .width(placeWidth)
            .build();
    load.active = active;
    place.active = active;
    return List.of(load, place);
  }

  /** Tells the player what Litematica did with {@code fileName}. */
  static void report(
      Minecraft minecraft, LitematicaAction action, Outcome outcome, String fileName) {
    Component file = Component.literal(fileName);
    switch (outcome) {
      case DONE ->
          show(
              minecraft,
              Component.translatable(
                  action == LitematicaAction.LOAD
                      ? "schematicvault.litematica.loaded"
                      : "schematicvault.litematica.placed"),
              file);
      case UNREADABLE ->
          show(minecraft, Component.translatable("schematicvault.litematica.unreadable"), file);
      case NAME_REJECTED ->
          show(
              minecraft,
              Component.translatable("schematicvault.litematica.name_rejected"),
              Component.translatable("schematicvault.litematica.name_rejected.hint", fileName));
      case DISABLED ->
          show(
              minecraft,
              Component.translatable("schematicvault.litematica.disabled"),
              Component.translatable("schematicvault.litematica.disabled.hint"));
    }
  }

  /**
   * Loads or places {@code file} with Litematica and says how it went; null when the player left
   * the world meanwhile (Litematica has nowhere to put it) and nothing was called.
   *
   * @param name the placement's name: the design's title, or the edited copy's
   */
  static @Nullable Outcome deliver(
      Minecraft minecraft,
      LitematicaBridge litematica,
      LitematicaAction action,
      Path file,
      SchematicFormat format,
      String name) {
    LocalPlayer player = minecraft.player;
    if (player == null) {
      return null;
    }
    String fileName = file.getFileName().toString();
    Outcome outcome =
        switch (action) {
          case LOAD -> litematica.load(file, format);
          case PLACE ->
              litematica.place(
                  file, format, PlacementNames.of(name, fileName), player.blockPosition());
        };
    report(minecraft, action, outcome, fileName);
    return outcome;
  }

  /** A toast in the Litematica slot, also for a failure before Litematica was called. */
  static void show(Minecraft minecraft, Component title, Component message) {
    SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, title, message);
  }
}
