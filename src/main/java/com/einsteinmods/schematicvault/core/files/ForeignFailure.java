package com.einsteinmods.schematicvault.core.files;

/**
 * What a failure thrown from another mod's code while it read one file means for the next call. A
 * {@link LinkageError} (a missing class, method or field) says that mod's internals changed under
 * the version the call was written for: every later call would fail the same way. Anything else
 * happened while reading that one file: other files may still work.
 */
public enum ForeignFailure {
  /** Only the file being read failed: report it and keep going. */
  ONE_FILE,
  /** The other mod no longer matches the calls made to it: stop calling it. */
  INCOMPATIBLE;

  /** The kind of {@code failure}. */
  public static ForeignFailure of(Throwable failure) {
    return failure instanceof LinkageError ? INCOMPATIBLE : ONE_FILE;
  }
}
