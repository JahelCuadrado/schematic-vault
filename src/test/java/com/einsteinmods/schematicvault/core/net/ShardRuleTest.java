package com.einsteinmods.schematicvault.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShardRuleTest {
  @Test
  void matchesTheShardsTheApiServesRecordsFrom() {
    // abf-14645's recordUrl in the API 2.0.0 is on sov-shard-05 (of 32).
    assertEquals(5, ShardRule.shardOf("abf-14645", 32));
  }

  @Test
  void staysInRange() {
    assertEquals(0, ShardRule.shardOf("abf-14645", 1));
  }
}
