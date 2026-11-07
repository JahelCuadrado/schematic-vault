package com.einsteinmods.schematicvault.client.litematica;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * What the vault asks of Litematica, with no Litematica type in sight: only {@link
 * LitematicaIntegration} touches its classes, and that class is loaded only when Fabric reports
 * Litematica installed ({@link #create}). Without it the vault gets {@link NoLitematica} and shows
 * no Litematica button. Render thread only (Litematica's own state is not thread-safe).
 */
public interface LitematicaBridge {
  /** Litematica's mod id in {@code fabric.mod.json}. */
  String MOD_ID = "litematica";

  /** Whether the buttons are shown: Litematica is installed and no call to it has failed. */
  boolean available();

  /** Whether Litematica can load files of {@code format}. */
  boolean supports(SchematicFormat format);

  /** Adds the file to Litematica's loaded schematics (once: a file already loaded is reused). */
  Outcome load(Path file, SchematicFormat format);

  /**
   * Loads the file as {@link #load} does, then creates an enabled, rendered placement of it named
   * {@code name} at {@code origin} and selects it, ready to move and rotate with Litematica's
   * tools.
   */
  Outcome place(Path file, SchematicFormat format, String name, BlockPos origin);

  /** What a call to Litematica did. */
  enum Outcome {
    DONE,
    /**
     * Litematica could not read the file (it says why in its own message) or failed inside while
     * reading it: only this call failed.
     */
    UNREADABLE,
    /** Litematica rewrites this file name and would look for another file (see the bridge). */
    NAME_REJECTED,
    /**
     * A class or method the bridge calls is gone (Litematica's internals changed): the integration
     * is now off for this vault session.
     */
    DISABLED
  }

  /**
   * A new bridge (one per vault session): {@link LitematicaIntegration} when Litematica is loaded,
   * else the no-op. Creating the integration loads none of Litematica's classes until its first
   * call.
   */
  static LitematicaBridge create() {
    if (FabricLoader.getInstance().isModLoaded(MOD_ID)) {
      return new LitematicaIntegration();
    }
    return NoLitematica.INSTANCE;
  }
}
