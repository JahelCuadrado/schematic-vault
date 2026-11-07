package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.preview.BlockList;
import com.einsteinmods.schematicvault.client.preview.EditedFile;
import com.einsteinmods.schematicvault.client.preview.PreviewController;
import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Saves the shown file's edits as a new file and remembers the last copy, reused while the edits it
 * holds stay the same. Render thread.
 */
final class EditedCopies {
  /** Writes an edited file under a free name; the saved path, or a failure. */
  @FunctionalInterface
  interface Writer {
    CompletableFuture<Path> write(EditedFile edited, String name);
  }

  /** Hands a saved file to Litematica. */
  @FunctionalInterface
  interface Delivery {
    void deliver(LitematicaAction action, Path file, SchematicFormat format, String name);
  }

  private final Minecraft minecraft;
  private final PreviewController previews;
  private final Writer writer;
  private final Delivery delivery;
  private @Nullable Saved last;

  EditedCopies(Minecraft minecraft, PreviewController previews, Writer writer, Delivery delivery) {
    this.minecraft = minecraft;
    this.previews = previews;
    this.writer = writer;
    this.delivery = delivery;
  }

  /** Hands the last copy saved here to Litematica, if it still holds the current edits. */
  boolean sendLast(LitematicaAction action) {
    Saved saved = last;
    BlockList blocks = previews.blockList();
    if (saved == null || blocks == null || saved.blocks() != blocks) {
      return false;
    }
    delivery.deliver(action, saved.path(), saved.format(), saved.name());
    return true;
  }

  /**
   * Saves the shown file with its replacements as {@code name}, then hands the copy to Litematica
   * if {@code then} is set; completes on the render thread, after that.
   */
  CompletableFuture<Path> save(String name, String author, @Nullable LitematicaAction then) {
    SchematicFormat format = previews.editedFormat();
    BlockList blocks = previews.blockList();
    return previews
        .editedFile(name, author)
        .thenCompose(edited -> writer.write(edited, name))
        .whenCompleteAsync(
            (saved, failure) -> {
              if (saved == null || format == null) {
                if (then != null) {
                  LitematicaButtons.show(
                      minecraft,
                      Component.translatable("schematicvault.editor.save_failed"),
                      Component.literal(name));
                }
                return;
              }
              if (blocks != null) {
                last = new Saved(saved, name, format, blocks);
              }
              if (then != null) {
                delivery.deliver(then, saved, format, name);
              }
            },
            minecraft);
  }

  /**
   * An edited copy saved here.
   *
   * @param blocks the block list it was saved from: the same instance while the edits stay the same
   */
  private record Saved(Path path, String name, SchematicFormat format, BlockList blocks) {}
}
