package com.einsteinmods.schematicvault.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiLanguagesTest {
  private static final List<String> API =
      List.of("en", "es", "zh-CN", "pt-BR", "ru", "de", "fr", "ja", "ko", "it", "pl", "tr");

  @Test
  void sameLanguageAndRegionFirst() {
    assertEquals("pt-BR", ApiLanguages.choose("pt_br", API));
    assertEquals("zh-CN", ApiLanguages.choose("zh_cn", API));
  }

  @Test
  void thenTheSameLanguage() {
    assertEquals("es", ApiLanguages.choose("es_mx", API));
    assertEquals("es", ApiLanguages.choose("es_es", API));
    assertEquals("zh-CN", ApiLanguages.choose("zh_tw", API));
    assertEquals("pt-BR", ApiLanguages.choose("pt_pt", API));
    assertEquals("de", ApiLanguages.choose("de_de", API));
  }

  @Test
  void elseEnglish() {
    assertEquals("en", ApiLanguages.choose("nl_nl", API));
    assertEquals("en", ApiLanguages.choose("uk_ua", API));
    assertEquals("en", ApiLanguages.choose("es_es", List.of()));
  }
}
