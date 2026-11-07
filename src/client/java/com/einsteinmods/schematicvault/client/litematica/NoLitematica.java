package com.einsteinmods.schematicvault.client.litematica;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;

/** The bridge without Litematica: nothing is available, so no button ever calls it. */
enum NoLitematica implements LitematicaBridge {
  INSTANCE;

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public boolean supports(SchematicFormat format) {
    return false;
  }

  @Override
  public Outcome load(Path file, SchematicFormat format) {
    return Outcome.DISABLED;
  }

  @Override
  public Outcome place(Path file, SchematicFormat format, String name, BlockPos origin) {
    return Outcome.DISABLED;
  }
}
