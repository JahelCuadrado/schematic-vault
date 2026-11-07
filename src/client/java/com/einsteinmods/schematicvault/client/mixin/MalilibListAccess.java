package com.einsteinmods.schematicvault.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Changes the row height of one MaLiLib list: {@code WidgetListBase#browserEntryHeight}, which
 * every row, click and scroll position of that list follows, and {@code #reCreateListEntryWidgets},
 * which lays the rows out again. Both are protected. The target is named by string, so no MaLiLib
 * type is here. Optional: {@link Pseudo} skips the mixin without MaLiLib; callers check {@code
 * instanceof}, because a mixin that cannot apply only logs. Adds no code to any path of a list:
 * only its caller pays, see {@code LitematicaIntegration#fitRows}.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.gui.widgets.WidgetListBase")
public interface MalilibListAccess {
  @Accessor("browserEntryHeight")
  int schematicvault$rowHeight();

  @Accessor("browserEntryHeight")
  void schematicvault$setRowHeight(int height);

  @Invoker("reCreateListEntryWidgets")
  void schematicvault$layOutRows();
}
