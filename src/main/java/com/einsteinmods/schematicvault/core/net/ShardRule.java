package com.einsteinmods.schematicvault.core.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Which of the API's shard hosts holds a design's record, as its manifest states it ({@code
 * shards.recordRule}): {@code floor(firstByte(sha256(utf8(id))) * count / 256)}. If the API changes
 * the rule, records are looked for on the wrong host and fail with 404. Files and images, named by
 * their own SHA-256, follow the same formula on that digest's first byte ({@link #shardOfByte}).
 */
public final class ShardRule {
  private static final String SHA_256 = "SHA-256";
  private static final int BYTE_VALUES = 256;
  private static final int UNSIGNED_BYTE = 0xFF;

  private ShardRule() {}

  /** The shard of {@code id} among {@code count} (at least 1). One SHA-256 of the id. */
  public static int shardOf(String id, int count) {
    try {
      byte[] digest =
          MessageDigest.getInstance(SHA_256).digest(id.getBytes(StandardCharsets.UTF_8));
      return shardOfByte(digest[0] & UNSIGNED_BYTE, count);
    } catch (NoSuchAlgorithmException impossible) {
      // Every Java runtime ships SHA-256.
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * The shard of a digest whose first byte is {@code firstByte} (0 to 255) among {@code count} (at
   * least 1): {@code floor(firstByte * count / 256)}.
   */
  public static int shardOfByte(int firstByte, int count) {
    return firstByte * count / BYTE_VALUES;
  }
}
