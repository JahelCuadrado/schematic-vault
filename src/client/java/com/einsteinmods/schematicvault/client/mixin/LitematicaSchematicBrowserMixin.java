package com.einsteinmods.schematicvault.client.mixin;

import com.einsteinmods.schematicvault.client.litematica.LitematicaBrowserPanel;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the preview over the info panel when Litematica's {@code
 * WidgetSchematicBrowser#drawSelectedSchematicInfo} ends. At every {@code RETURN}, not {@code
 * TAIL}: the method has three, one for no selection and one for a file without data, and only the
 * last is {@code TAIL}. Its locals say where the panel is and where its data ended, so this draws
 * from the same numbers: its ints are {@code x}, {@code y} and {@code height}, in this order, at
 * all three. Its {@code GuiContext} extends vanilla's {@code GuiGraphicsExtractor} and shares its
 * GUI render state, which is what a picture-in-picture needs; {@link Coerce} takes it, and the
 * entry, without naming a Litematica or MaLiLib type. Optional: {@link Pseudo} skips the mixin
 * without Litematica, {@code require = 0} skips the injection if the method is gone, and a config
 * not {@code required} only logs one that cannot apply. {@code this} goes on as {@code Object}: the
 * list has finished walking its rows here, so its height may change. Cost per frame the panel
 * draws: one call, see {@link LitematicaBrowserPanel#draw}; no other screen pays anything.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser")
abstract class LitematicaSchematicBrowserMixin {
  @Inject(method = "drawSelectedSchematicInfo", at = @At("RETURN"), require = 0)
  private void schematicvault$drawPreview(
      @Coerce GuiGraphicsExtractor graphics,
      @Coerce Object entry,
      CallbackInfo info,
      @Local(ordinal = 0) int x,
      @Local(ordinal = 1) int y,
      @Local(ordinal = 2) int height) {
    LitematicaBrowserPanel.draw(this, graphics, entry, x, y, height);
  }
}
