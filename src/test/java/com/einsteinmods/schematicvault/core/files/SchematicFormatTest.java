package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchematicFormatTest {
  @Test
  void aFileNameGivesItsFormat() {
    assertEquals(
        Optional.of(SchematicFormat.LITEMATIC), SchematicFormat.fromFileName("a.litematic"));
    assertEquals(Optional.of(SchematicFormat.SCHEM), SchematicFormat.fromFileName("b.c.schem"));
    assertEquals(
        Optional.of(SchematicFormat.SCHEMATIC), SchematicFormat.fromFileName("d.schematic"));
    assertEquals(Optional.of(SchematicFormat.NBT), SchematicFormat.fromFileName("e.nbt"));
  }

  @Test
  void otherFilesHaveNoFormat() {
    assertEquals(Optional.empty(), SchematicFormat.fromFileName("materials.json"));
    assertEquals(Optional.empty(), SchematicFormat.fromFileName("list.txt"));
    assertEquals(Optional.empty(), SchematicFormat.fromFileName("no extension"));
    assertEquals(Optional.empty(), SchematicFormat.fromFileName("house.schem.bak"));
    assertEquals(Optional.empty(), SchematicFormat.fromFileName("upper.NBT"));
  }
}
