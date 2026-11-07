package com.einsteinmods.schematicvault.client.net;

/** Why a request or download failed, as far as the player needs to know. */
public enum FailureKind {
  /** No connection, a timeout or the server is down. */
  NETWORK,
  /** The server answered with an error or with data the mod cannot use. */
  SERVER,
  /** The downloaded bytes do not match the SHA-256 the API published. */
  CHECKSUM,
  /** The file could not be written. */
  DISK
}
