package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FileSizeTest {
  @Test
  void picksTheUnitAndDecimals() {
    assertEquals(new FileSize("512", FileSize.Unit.BYTES), FileSize.of(512));
    assertEquals(new FileSize("4.0", FileSize.Unit.KILOBYTES), FileSize.of(4096));
    assertEquals(new FileSize("52", FileSize.Unit.KILOBYTES), FileSize.of(52_945));
    assertEquals(new FileSize("9.5", FileSize.Unit.MEGABYTES), FileSize.of(9_961_472));
    assertEquals(new FileSize("0", FileSize.Unit.BYTES), FileSize.of(-1));
  }
}
