package com.einsteinmods.schematicvault.core.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DataVersionsTest {
  @Test
  void aFileWithoutVersionIsReadAsTheLastGameBeforeTheFlattening() {
    assertEquals(1343, DataVersions.forFixer(0));
    assertEquals(1343, DataVersions.forFixer(1));
  }

  @Test
  void aDeclaredVersionStaysAsItIs() {
    assertEquals(1343, DataVersions.forFixer(1343));
    assertEquals(1519, DataVersions.forFixer(1519));
    assertEquals(4903, DataVersions.forFixer(4903));
  }

  @Test
  void aNegativeVersionIsNotReadAsOldNames() {
    assertEquals(-1, DataVersions.forFixer(-1));
  }

  @Test
  void onlyZeroAndOneMeanNoVersion() {
    assertTrue(DataVersions.isUnversioned(0));
    assertTrue(DataVersions.isUnversioned(1));
    assertFalse(DataVersions.isUnversioned(-1));
    assertFalse(DataVersions.isUnversioned(1343));
  }
}
