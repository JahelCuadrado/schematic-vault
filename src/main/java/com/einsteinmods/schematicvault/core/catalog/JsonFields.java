package com.einsteinmods.schematicvault.core.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Tolerant reads of API JSON: a missing field or one of another type reads as absent. */
final class JsonFields {
  private static final String NAME = "name";

  private JsonFields() {}

  static @Nullable String string(JsonObject object, String key) {
    JsonElement element = object.get(key);
    if (element instanceof JsonPrimitive primitive && primitive.isString()) {
      return primitive.getAsString();
    }
    return null;
  }

  static String string(JsonObject object, String key, String fallback) {
    String value = string(object, key);
    return value == null ? fallback : value;
  }

  static long number(JsonObject object, String key, long fallback) {
    JsonElement element = object.get(key);
    if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
      return primitive.getAsLong();
    }
    return fallback;
  }

  static int integer(JsonObject object, String key, int fallback) {
    long value = number(object, key, fallback);
    return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
  }

  /** A JSON boolean; anything else reads as {@code false}. */
  static boolean bool(JsonObject object, String key) {
    return object.get(key) instanceof JsonPrimitive primitive
        && primitive.isBoolean()
        && primitive.getAsBoolean();
  }

  static @Nullable JsonObject object(JsonObject object, String key) {
    return object.get(key) instanceof JsonObject child ? child : null;
  }

  static JsonArray array(JsonObject object, String key) {
    return object.get(key) instanceof JsonArray child ? child : new JsonArray();
  }

  /** The strings of an array; an object element gives its {@code name} (authors in records). */
  static List<String> strings(JsonObject object, String key) {
    List<String> values = new ArrayList<>();
    for (JsonElement element : array(object, key)) {
      if (element instanceof JsonPrimitive primitive && primitive.isString()) {
        values.add(primitive.getAsString());
      } else if (element instanceof JsonObject named) {
        String name = string(named, NAME);
        if (name != null) {
          values.add(name);
        }
      }
    }
    return values;
  }
}
