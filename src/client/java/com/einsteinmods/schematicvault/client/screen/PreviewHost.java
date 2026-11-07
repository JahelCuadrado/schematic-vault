package com.einsteinmods.schematicvault.client.screen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * What {@link PreviewScreen} needs from the screen that opened it: the vault, or Litematica's
 * schematic browser ({@link BrowserPreview}). The file shown lives in their {@code
 * PreviewController}.
 */
interface PreviewHost {
  /** Leaves the big view for the screen that opened it. */
  void closePreview();

  /** Hands the shown file, as it is, to Litematica. */
  void sendShown(LitematicaAction action);

  /** Hands the last edited copy to Litematica if it still holds the current edits. */
  boolean sendLastEdited(LitematicaAction action);

  /** Asks the edited copy's name over {@code returnTo}, which the name screen returns to. */
  void askEditedName(Screen returnTo, Consumer<String> onSave);

  /**
   * Saves the shown file with its replacements as {@code name}, then hands the copy to Litematica
   * if {@code then} is set; completes on the render thread, after that.
   */
  CompletableFuture<Path> saveEdited(String name, @Nullable LitematicaAction then);
}
