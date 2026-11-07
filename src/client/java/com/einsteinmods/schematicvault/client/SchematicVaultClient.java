package com.einsteinmods.schematicvault.client;

import com.einsteinmods.schematicvault.client.cache.CatalogPrefetch;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBridge;
import com.einsteinmods.schematicvault.client.litematica.LitematicaBrowserPanel;
import com.einsteinmods.schematicvault.client.preview.PreviewRenderer;
import com.einsteinmods.schematicvault.client.screen.VaultScreen;
import com.einsteinmods.schematicvault.config.JsonConfigFile;
import com.einsteinmods.schematicvault.config.VaultConfigFile;
import com.einsteinmods.schematicvault.core.config.VaultSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Client entrypoint: registers the key that opens the vault, the 3D preview's renderer and a
 * background catalog refresh on joining a world ({@link CatalogPrefetch}, once per join), and
 * nothing else. The config file is read each time the vault (or its settings screen) opens, so
 * edits apply without restarting, and written only by the settings screen's Save. No class with
 * network, cache or screen code loads before then (or the first join): while closed the mod costs
 * one key counter read per tick; with Litematica, one more field read for its browser's preview
 * ({@code LitematicaBrowserPanel#onClientTick}). The preview renderer is created by vanilla with
 * its GUI renderer and holds no GPU memory of its own until a preview is drawn; the GUI only calls
 * it for a frame where a preview asked for it.
 */
public final class SchematicVaultClient implements ClientModInitializer {
  public static final String MOD_ID = "schematicvault";

  private static @Nullable SchematicVaultKeyMappings keyMappings;

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
  }

  /** The settings in the config file now; defaults (written to the file) when it is missing. */
  public static VaultSettings settings() {
    return configFile().load().toSettings();
  }

  /**
   * Writes {@code settings} to the config file (once, atomically); the next vault opened uses it.
   */
  public static void saveSettings(VaultSettings settings) {
    configFile().save(VaultConfigFile.of(settings));
  }

  private static JsonConfigFile<VaultConfigFile> configFile() {
    return new JsonConfigFile<>(
        FabricLoader.getInstance().getConfigDir().resolve(VaultConfigFile.FILE_NAME),
        VaultConfigFile.class,
        VaultConfigFile.DEFAULTS);
  }

  /** The vault's key mappings, registered at client start. */
  public static SchematicVaultKeyMappings keyMappings() {
    SchematicVaultKeyMappings mappings = keyMappings;
    if (mappings == null) {
      throw new IllegalStateException("Schematic Vault keys are not registered yet");
    }
    return mappings;
  }

  /** Opens the vault; closing it returns to {@code parent}. */
  public static void openVault(Minecraft minecraft, @Nullable Screen parent) {
    minecraft.gui.setScreen(new VaultScreen(parent, VaultSession.open(minecraft, settings())));
  }

  @Override
  public void onInitializeClient() {
    SchematicVaultKeyMappings mappings = new SchematicVaultKeyMappings();
    keyMappings = mappings;
    ClientTickEvents.END_CLIENT_TICK.register(mappings::onClientTick);
    if (FabricLoader.getInstance().isModLoaded(LitematicaBridge.MOD_ID)) {
      ClientTickEvents.END_CLIENT_TICK.register(LitematicaBrowserPanel::onClientTick);
    }
    ClientPlayConnectionEvents.JOIN.register(
        (listener, sender, minecraft) -> CatalogPrefetch.onJoin(minecraft));
    PictureInPictureRendererRegistry.register(context -> new PreviewRenderer());
  }
}
