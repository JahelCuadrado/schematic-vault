package com.einsteinmods.schematicvault.client.mixin;

import com.einsteinmods.schematicvault.client.litematica.LitematicaRowImages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the rows of Litematica's "Loaded schematics" and "Schematic placements" lists to the
 * thumbnails: {@code WidgetSchematicEntry#render} and {@code WidgetSchematicPlacement#render} draw
 * their type icon at {@code x + 2}, so a thumbnail drawn at their end covers it, and their name's
 * {@code drawString}, the only one in each, moves past the image. {@link Coerce} takes their {@code
 * GuiContext} as vanilla's {@code GuiGraphicsExtractor}, and {@code this} goes on as {@code
 * Object}, so no Litematica type is in the mixin. The call has no owner in {@code target} because
 * each class calls it on itself. Optional: {@link Pseudo} skips the mixin without Litematica,
 * {@code require = 0} skips an injection if its call is gone. Cost per row drawn: see {@link
 * LitematicaRowImages}.
 */
@Pseudo
@Mixin(
    targets = {
      "fi.dy.masa.litematica.gui.widgets.WidgetSchematicEntry",
      "fi.dy.masa.litematica.gui.widgets.WidgetSchematicPlacement"
    })
abstract class LitematicaSchematicRowMixin {
  @Inject(method = "render", at = @At("TAIL"), require = 0)
  private void schematicvault$drawThumbnail(
      @Coerce GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      boolean selected,
      CallbackInfo info) {
    LitematicaRowImages.drawSchematicRow(graphics, this);
  }

  @ModifyArg(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target = "drawString(Lfi/dy/masa/malilib/render/GuiContext;IIILjava/lang/String;)V"),
      index = 1,
      require = 0)
  private int schematicvault$moveName(int x) {
    return LitematicaRowImages.nameX(x);
  }
}
