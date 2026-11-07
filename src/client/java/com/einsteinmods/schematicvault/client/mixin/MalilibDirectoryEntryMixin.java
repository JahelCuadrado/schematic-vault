package com.einsteinmods.schematicvault.client.mixin;

import com.einsteinmods.schematicvault.client.litematica.LitematicaBrowserPanel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands each row of a MaLiLib file list to the thumbnails between its icon and its name: {@code
 * WidgetDirectoryEntry#render} draws the icon, then calls {@code getDisplayName} once for the text,
 * so a thumbnail drawn there covers the icon and stays under the hover text at its end. {@link
 * Coerce} takes its {@code GuiContext} as vanilla's {@code GuiGraphicsExtractor}, and {@code this}
 * goes on as {@code Object}, so no MaLiLib type is in the mixin. Optional: {@link Pseudo} skips the
 * mixin without MaLiLib, {@code require = 0} skips the injection if the call is gone. Runs for
 * every MaLiLib file list; {@link LitematicaBrowserPanel#drawRow} acts only for Litematica's
 * schematic browser. Cost per row drawn: see there.
 *
 * <p>The name's {@code drawString} is the row's only one; in the taller rows of that browser its x
 * moves past the image ({@link LitematicaBrowserPanel#rowTextX}): a few field reads per row.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry")
abstract class MalilibDirectoryEntryMixin {
  @Inject(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lfi/dy/masa/malilib/gui/widgets/WidgetDirectoryEntry;getDisplayName()Ljava/lang/String;"),
      require = 0)
  private void schematicvault$drawThumbnail(
      @Coerce GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      boolean selected,
      CallbackInfo info) {
    LitematicaBrowserPanel.drawRow(graphics, this, selected);
  }

  @ModifyArg(
      method = "render",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lfi/dy/masa/malilib/gui/widgets/WidgetDirectoryEntry;drawString(Lfi/dy/masa/malilib/render/GuiContext;IIILjava/lang/String;)V"),
      index = 1,
      require = 0)
  private int schematicvault$moveName(int x) {
    return LitematicaBrowserPanel.rowTextX(x);
  }
}
