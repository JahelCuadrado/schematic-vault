package com.einsteinmods.schematicvault.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** The key that opens the vault, unbound by default so it never clashes with vanilla or mods. */
public final class SchematicVaultKeyMappings {
  private static final KeyMapping.Category CATEGORY =
      KeyMapping.Category.register(SchematicVaultClient.id("main"));

  private final KeyMapping openVault =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping("key.schematicvault.open", InputConstants.UNKNOWN.getValue(), CATEGORY));

  /** The key row the settings screen edits; Controls shows the same mapping. */
  public KeyMapping openVault() {
    return openVault;
  }

  /**
   * Per client tick: one click counter read while the key is not pressed. Opens the vault only
   * while playing (no screen open), so a burst of presses never stacks screens.
   */
  public void onClientTick(Minecraft minecraft) {
    while (openVault.consumeClick()) {
      if (minecraft.gui.screen() == null && minecraft.player != null) {
        SchematicVaultClient.openVault(minecraft, null);
      }
    }
  }
}
