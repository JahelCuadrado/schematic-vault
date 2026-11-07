package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;
import java.util.Locale;

/**
 * Picks the API locale for the game language: the same language and region ({@code pt_br} to {@code
 * pt-BR}), else the same language ({@code es_mx} to {@code es}, {@code zh_tw} to {@code zh-CN}),
 * else English.
 */
public final class ApiLanguages {
  public static final String FALLBACK = "en";

  private static final char MINECRAFT_SEPARATOR = '_';
  private static final char API_SEPARATOR = '-';

  private ApiLanguages() {}

  /**
   * @param minecraftCode the game's language code, e.g. {@code es_es}
   * @param available the manifest's languages, e.g. {@code [en, es, zh-CN]}
   */
  public static String choose(String minecraftCode, List<String> available) {
    String code =
        minecraftCode.toLowerCase(Locale.ROOT).replace(MINECRAFT_SEPARATOR, API_SEPARATOR);
    int separator = code.indexOf(API_SEPARATOR);
    String language = separator < 0 ? code : code.substring(0, separator);
    for (String candidate : available) {
      if (candidate.equalsIgnoreCase(code)) {
        return candidate;
      }
    }
    for (String candidate : available) {
      if (candidate.equalsIgnoreCase(language)) {
        return candidate;
      }
    }
    String regional = language + API_SEPARATOR;
    for (String candidate : available) {
      if (candidate.toLowerCase(Locale.ROOT).startsWith(regional)) {
        return candidate;
      }
    }
    return FALLBACK;
  }
}
