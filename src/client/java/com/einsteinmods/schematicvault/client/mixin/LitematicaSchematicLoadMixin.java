package com.einsteinmods.schematicvault.client.mixin;

import com.einsteinmods.schematicvault.client.litematica.LitematicaBrowserPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * At the end of Litematica's {@code GuiSchematicLoad#createButtons}, moves its menu button off a
 * row that does not hold it and puts the info panel's button back. That method runs on every {@code
 * initGui} and on every selection ({@code onSelectionChange} clears all the buttons, with no {@code
 * initGui}, so Fabric's {@code ScreenEvents.AFTER_INIT} would lose it). Optional: {@link Pseudo}
 * skips the mixin without Litematica and {@code require = 0} skips the injection if the method is
 * gone. The target is named by string and no Litematica type appears here. Runs only when that
 * screen builds its buttons: nothing per frame or tick.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiSchematicLoad")
abstract class LitematicaSchematicLoadMixin {
  @Inject(method = "createButtons", at = @At("TAIL"), require = 0)
  private void schematicvault$addPanelButton(CallbackInfo info) {
    LitematicaBrowserPanel.addButton(this);
  }
}
