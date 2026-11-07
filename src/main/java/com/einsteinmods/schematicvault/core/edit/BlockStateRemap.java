package com.einsteinmods.schematicvault.core.edit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One state of an old block as a state of the new block: the new block's default state with each
 * property copied when the new block has a property of that name and the value is one of its
 * values; every other property keeps the new block's default. Pure: the client describes the new
 * block's properties as plain data ({@link TargetBlock}).
 */
public final class BlockStateRemap {
  private static final char PROPERTIES_START = '[';
  private static final char PROPERTIES_END = ']';
  private static final char PROPERTY_SEPARATOR = ',';
  private static final char VALUE_SEPARATOR = '=';

  private BlockStateRemap() {}

  /**
   * A property of a block: its values, as the game names them, and the default one.
   *
   * @param values the values it accepts
   * @param defaultValue the value of the block's default state
   */
  public record PropertyValues(List<String> values, String defaultValue) {
    public PropertyValues {
      values = List.copyOf(values);
    }
  }

  /**
   * The block an old block becomes.
   *
   * @param id its id ({@code minecraft:spruce_stairs})
   * @param properties its properties in the block's own order
   */
  public record TargetBlock(String id, Map<String, PropertyValues> properties) {
    public TargetBlock {
      properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** A block without properties ({@code minecraft:air}, {@code minecraft:stone}). */
    public static TargetBlock plain(String id) {
      return new TargetBlock(id, Map.of());
    }
  }

  /**
   * The new state.
   *
   * @param properties every property of the new block with its value, in the block's order
   * @param lostProperties whether some property of the old state could not be carried over
   */
  public record Remapped(String id, Map<String, String> properties, boolean lostProperties) {
    public Remapped {
      properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** The state as text ({@code minecraft:spruce_stairs[facing=east,half=top]}). */
    public String state() {
      if (properties.isEmpty()) {
        return id;
      }
      StringBuilder text = new StringBuilder(id).append(PROPERTIES_START);
      boolean first = true;
      for (Map.Entry<String, String> property : properties.entrySet()) {
        if (!first) {
          text.append(PROPERTY_SEPARATOR);
        }
        first = false;
        text.append(property.getKey()).append(VALUE_SEPARATOR).append(property.getValue());
      }
      return text.append(PROPERTIES_END).toString();
    }
  }

  /** The state of {@code target} for an old state with {@code oldProperties}. */
  public static Remapped remap(Map<String, String> oldProperties, TargetBlock target) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (Map.Entry<String, PropertyValues> property : target.properties().entrySet()) {
      String old = oldProperties.get(property.getKey());
      PropertyValues values = property.getValue();
      properties.put(
          property.getKey(),
          old != null && values.values().contains(old) ? old : values.defaultValue());
    }
    boolean lost = false;
    for (Map.Entry<String, String> old : oldProperties.entrySet()) {
      if (!old.getValue().equals(properties.get(old.getKey()))) {
        lost = true;
        break;
      }
    }
    return new Remapped(target.id(), properties, lost);
  }

  /**
   * {@code base} with the value of each of {@code overrides} that {@code target} accepts; the rest
   * of {@code overrides} is ignored.
   */
  public static Map<String, String> overlay(
      Map<String, String> base, Map<String, String> overrides, TargetBlock target) {
    Map<String, String> properties = new LinkedHashMap<>(base);
    for (Map.Entry<String, String> override : overrides.entrySet()) {
      PropertyValues values = target.properties().get(override.getKey());
      if (values != null && values.values().contains(override.getValue())) {
        properties.put(override.getKey(), override.getValue());
      }
    }
    return properties;
  }
}
