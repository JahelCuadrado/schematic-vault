package com.einsteinmods.schematicvault.client.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the private button list of MaLiLib's {@code GuiBase}, which has no getter: {@code
 * GuiSchematicLoad#createButtons} makes its "Litematica menu" button in a local. The target is
 * named by string and the list's element type stays out, so no MaLiLib type is here. Optional:
 * {@link Pseudo} skips the mixin without MaLiLib; callers check {@code instanceof}, because a mixin
 * that cannot apply only logs. Adds no code to any path of the screen: nothing per frame or tick.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.gui.GuiBase")
public interface MalilibScreenAccess {
  @Accessor("buttons")
  List<?> schematicvault$buttons();
}
