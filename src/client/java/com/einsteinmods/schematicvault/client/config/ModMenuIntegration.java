package com.einsteinmods.schematicvault.client.config;

import com.einsteinmods.schematicvault.client.SchematicVaultClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.Minecraft;

/** Mod Menu "Configure" button; only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return parent ->
        new VaultSettingsScreen(
            SchematicVaultClient.settings(),
            SchematicVaultClient.keyMappings().openVault(),
            saved -> Minecraft.getInstance().gui.setScreen(parent));
  }
}
