package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SafeFileNamesTest {
  @Test
  void keepsReadableNamesAndUsesTheFormatsExtension() {
    assertEquals(
        "PistonFactory64k.litematic",
        SafeFileNames.fileName("PistonFactory64k.litematic", SchematicFormat.LITEMATIC));
    assertEquals(
        "Iron Farm (v2).schem",
        SafeFileNames.fileName("Iron Farm (v2).schem", SchematicFormat.SCHEM));
    assertEquals("farm.exe.nbt", SafeFileNames.fileName("farm.exe", SchematicFormat.NBT));
    assertEquals(
        "铁傀儡农场.litematic", SafeFileNames.fileName("铁傀儡农场.litematic", SchematicFormat.LITEMATIC));
  }

  @Test
  void pathsAndOddCharactersCannotEscapeTheFolder() {
    assertEquals(
        "etc_passwd.litematic",
        SafeFileNames.fileName("../../etc/passwd", SchematicFormat.LITEMATIC));
    assertEquals(
        "C_ Windows_x.schem", SafeFileNames.fileName("C:\\ Windows\\x", SchematicFormat.SCHEM));
    assertEquals("a_b.nbt", SafeFileNames.fileName("a<>:\"|?*b", SchematicFormat.NBT));
    assertEquals("evil_gpj.nbt", SafeFileNames.fileName("evil\u202Egpj", SchematicFormat.NBT));
  }

  @Test
  void hiddenEmptyAndReservedNamesAreMadeSafe() {
    assertEquals("bashrc.nbt", SafeFileNames.fileName(".bashrc", SchematicFormat.NBT));
    assertEquals(
        SafeFileNames.FALLBACK_BASE + ".litematic",
        SafeFileNames.fileName("...", SchematicFormat.LITEMATIC));
    assertEquals(
        SafeFileNames.FALLBACK_BASE + ".litematic",
        SafeFileNames.fileName(".litematic", SchematicFormat.LITEMATIC));
    assertEquals("_CON.schem", SafeFileNames.fileName("CON", SchematicFormat.SCHEM));
    assertEquals("_com1.x.nbt", SafeFileNames.fileName("com1.x", SchematicFormat.NBT));
  }

  @Test
  void longNamesAreCut() {
    String name = SafeFileNames.fileName("a".repeat(500), SchematicFormat.NBT);
    assertEquals(SafeFileNames.MAX_BASE_LENGTH + ".nbt".length(), name.length());
  }

  @Test
  void collisionsGetANumberBeforeTheExtension() {
    assertEquals("farm.litematic", SafeFileNames.unique("farm.litematic", Set.of()::contains));
    assertEquals(
        "farm (2).litematic",
        SafeFileNames.unique("farm.litematic", Set.of("farm.litematic")::contains));
    assertEquals(
        "farm (3).litematic",
        SafeFileNames.unique(
            "farm.litematic", Set.of("farm.litematic", "farm (2).litematic")::contains));
    assertThrows(IllegalStateException.class, () -> SafeFileNames.unique("x.nbt", name -> true));
  }

  @Test
  void everyFormatHasItsOwnExtension() {
    for (SchematicFormat format : SchematicFormat.values()) {
      assertTrue(SafeFileNames.fileName("x", format).endsWith(format.extension()));
      assertEquals(format, SchematicFormat.fromId(format.id()).orElseThrow());
      assertEquals(
          format, SchematicFormat.fromExtension(format.extension().substring(1)).orElseThrow());
    }
    assertEquals(".schem", SchematicFormat.fromId("sponge_schem").orElseThrow().extension());
    assertEquals(".schematic", SchematicFormat.SCHEMATIC.extension());
    assertEquals(".nbt", SchematicFormat.NBT.extension());
    assertTrue(SchematicFormat.fromId("schem").isEmpty());
    assertTrue(SchematicFormat.fromId("zip").isEmpty());
    assertTrue(SchematicFormat.fromExtension("sponge_schem").isEmpty());
    assertTrue(SchematicFormat.fromExtension("chem").isEmpty());
  }
}
