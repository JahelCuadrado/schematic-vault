package com.einsteinmods.schematicvault.core.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForeignFailureTest {
  @Test
  void linkageErrorsMeanTheOtherModChanged() {
    assertEquals(ForeignFailure.INCOMPATIBLE, ForeignFailure.of(new NoSuchMethodError("load")));
    assertEquals(
        ForeignFailure.INCOMPATIBLE, ForeignFailure.of(new NoClassDefFoundError("Holder")));
    assertEquals(
        ForeignFailure.INCOMPATIBLE, ForeignFailure.of(new IncompatibleClassChangeError()));
  }

  @Test
  void runtimeExceptionsFailOnlyThatFile() {
    assertEquals(ForeignFailure.ONE_FILE, ForeignFailure.of(new NullPointerException("converter")));
    assertEquals(ForeignFailure.ONE_FILE, ForeignFailure.of(new IllegalStateException()));
    assertEquals(ForeignFailure.ONE_FILE, ForeignFailure.of(new ArrayIndexOutOfBoundsException()));
  }
}
