package com.einsteinmods.schematicvault.core.catalog;

/** The API or the cache sent JSON without what the mod needs (or not JSON at all). */
public final class CatalogFormatException extends Exception {
  public CatalogFormatException(String message) {
    super(message);
  }

  public CatalogFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
