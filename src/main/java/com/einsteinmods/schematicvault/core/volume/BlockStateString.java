package com.einsteinmods.schematicvault.core.volume;

import com.einsteinmods.schematicvault.core.nbt.NbtTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.StringTag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Block states as text, the form every format can give: {@code minecraft:oak_stairs[facing=east]}.
 * Parsing is lenient (blanks trimmed, a missing namespace allowed); the client decides which names
 * and values the game knows.
 */
public final class BlockStateString {
  private static final String DEFAULT_NAMESPACE = "minecraft:";
  private static final char NAMESPACE_SEPARATOR = ':';
  private static final char PROPERTIES_START = '[';
  private static final char PROPERTIES_END = ']';
  private static final char PROPERTY_SEPARATOR = ',';
  private static final char VALUE_SEPARATOR = '=';
  private static final String NAME_KEY = "Name";
  private static final String PROPERTIES_KEY = "Properties";

  /** The game's air blocks: never part of a design's block count. */
  private static final Set<String> AIR =
      Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

  private BlockStateString() {}

  /**
   * A block state: its name, as written (namespace added when missing), and its properties in
   * order.
   */
  public record Parsed(String name, Map<String, String> properties) {
    public Parsed {
      properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
  }

  public static Parsed parse(String state) {
    String trimmed = state.strip();
    int start = trimmed.indexOf(PROPERTIES_START);
    String name = start < 0 ? trimmed : trimmed.substring(0, start).strip();
    Map<String, String> properties = new LinkedHashMap<>();
    if (start >= 0) {
      int end = trimmed.lastIndexOf(PROPERTIES_END);
      String inner = trimmed.substring(start + 1, end > start ? end : trimmed.length());
      for (String pair : inner.split(String.valueOf(PROPERTY_SEPARATOR))) {
        int equals = pair.indexOf(VALUE_SEPARATOR);
        if (equals > 0) {
          properties.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
        }
      }
    }
    return new Parsed(withNamespace(name), properties);
  }

  /** Whether {@code state} is one of the air blocks. */
  public static boolean isAir(String state) {
    int start = state.indexOf(PROPERTIES_START);
    String name = (start < 0 ? state : state.substring(0, start)).strip();
    return AIR.contains(withNamespace(name));
  }

  /** The text of a palette entry stored as NBT ({@code {Name, Properties}}). */
  public static String of(CompoundTag entry) {
    String name = entry.string(NAME_KEY).orElse("");
    CompoundTag properties = entry.compound(PROPERTIES_KEY).orElse(null);
    if (properties == null || properties.values().isEmpty()) {
      return name;
    }
    StringBuilder text = new StringBuilder(name).append(PROPERTIES_START);
    boolean first = true;
    for (Map.Entry<String, NbtTag> property : properties.values().entrySet()) {
      if (!(property.getValue() instanceof StringTag(String value))) {
        continue;
      }
      if (!first) {
        text.append(PROPERTY_SEPARATOR);
      }
      first = false;
      text.append(property.getKey()).append(VALUE_SEPARATOR).append(value);
    }
    return text.append(PROPERTIES_END).toString();
  }

  /**
   * A state as a palette entry stored as NBT ({@code {Name, Properties}}): the inverse of {@link
   * #of}.
   */
  public static CompoundTag compound(String state) {
    Parsed parsed = parse(state);
    Map<String, NbtTag> entry = new LinkedHashMap<>();
    entry.put(NAME_KEY, new StringTag(parsed.name()));
    if (!parsed.properties().isEmpty()) {
      Map<String, NbtTag> properties = new LinkedHashMap<>();
      parsed.properties().forEach((key, value) -> properties.put(key, new StringTag(value)));
      entry.put(PROPERTIES_KEY, new CompoundTag(properties));
    }
    return new CompoundTag(entry);
  }

  private static String withNamespace(String name) {
    return name.indexOf(NAMESPACE_SEPARATOR) < 0 ? DEFAULT_NAMESPACE + name : name;
  }
}
