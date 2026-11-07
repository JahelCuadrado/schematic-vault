package com.einsteinmods.schematicvault.client.mixin;

import com.einsteinmods.schematicvault.client.litematica.LitematicaMenuButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the vault's button at the end of Litematica's {@code GuiMainMenu#initGui}, which rebuilds
 * all the menu's buttons: on opening, on a resize, on returning from a sub-screen and when its own
 * mode buttons call it directly, the last of which never fires Fabric's {@code
 * ScreenEvents.AFTER_INIT}. Optional: {@link Pseudo} skips the mixin without Litematica and {@code
 * require = 0} skips the injection if the method is gone. The target is named by string and no
 * Litematica type appears here. Runs only when that menu builds: nothing per frame or tick.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiMainMenu")
abstract class LitematicaMainMenuMixin {
  @Inject(method = "initGui", at = @At("TAIL"), require = 0)
  private void schematicvault$addVaultButton(CallbackInfo info) {
    LitematicaMenuButton.add(this);
  }
}
