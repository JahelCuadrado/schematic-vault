package com.einsteinmods.schematicvault.core.volume;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pre-1.13 numeric blocks ({@code id:data}) as 1.13 block states, for MCEdit files. The table is a
 * copy of the API's {@code data/legacy-blocks.json} (from PrismarineJS minecraft-data, MIT),
 * shipped as {@link #RESOURCE}. A pair missing from it falls back to the id with data 0, then to
 * any state of the id (a chest facing another way), then to {@code legacy:<id>}, which no game
 * knows: the same order as the API, so block counts match.
 */
public final class LegacyBlocks {
  /** Classpath location of the table. */
  public static final String RESOURCE = "/schematicvault/legacy-blocks.json";

  /**
   * Namespace of the names given to numeric blocks the table does not have ({@code legacy:<id>}):
   * no game knows them.
   */
  public static final String NAMESPACE = "legacy";

  private static final String BLOCKS = "blocks";
  private static final String SEPARATOR = ":";
  private static final String UNKNOWN_PREFIX = NAMESPACE + SEPARATOR;

  /** Bits of the data value in a key. */
  private static final int DATA_BITS = 4;

  private final Map<Integer, String> byPair;
  private final Map<Integer, String> anyById;

  private LegacyBlocks(Map<Integer, String> byPair, Map<Integer, String> anyById) {
    this.byPair = Map.copyOf(byPair);
    this.anyById = Map.copyOf(anyById);
  }

  /** The table shipped with the mod. */
  public static LegacyBlocks load() throws IOException {
    try (InputStream input = LegacyBlocks.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IOException("missing " + RESOURCE);
      }
      return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
  }

  /** A table in the API's format: {@code {"blocks": {"id:data": "state"}}}. */
  public static LegacyBlocks parse(Reader json) throws IOException {
    Map<Integer, String> byPair = new HashMap<>();
    Map<Integer, Integer> lowestData = new HashMap<>();
    Map<Integer, String> anyById = new HashMap<>();
    try {
      JsonObject blocks = JsonParser.parseReader(json).getAsJsonObject().getAsJsonObject(BLOCKS);
      if (blocks == null) {
        throw new IOException("no " + BLOCKS + " table");
      }
      for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
        String[] parts = entry.getKey().split(SEPARATOR);
        if (parts.length != 2) {
          continue;
        }
        int id = Integer.parseInt(parts[0]);
        int data = Integer.parseInt(parts[1]);
        String state = entry.getValue().getAsString();
        byPair.put(key(id, data), state);
        Integer lowest = lowestData.get(id);
        if (lowest == null || data < lowest) {
          lowestData.put(id, data);
          anyById.put(id, state);
        }
      }
    } catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
      throw new IOException("bad legacy block table", exception);
    }
    return new LegacyBlocks(byPair, anyById);
  }

  /** The block state of a numeric block. */
  public String state(int id, int data) {
    String exact = byPair.get(key(id, data));
    if (exact != null) {
      return exact;
    }
    String plain = byPair.get(key(id, 0));
    if (plain != null) {
      return plain;
    }
    String any = anyById.get(id);
    return any != null ? any : UNKNOWN_PREFIX + id;
  }

  private static int key(int id, int data) {
    return id << DATA_BITS | data;
  }
}
