package com.einsteinmods.schematicvault.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebLinksTest {
  @Test
  void onlyWebPagesWithAHost() {
    assertTrue(WebLinks.parse("https://discord.com/channels/1/2").isPresent());
    assertTrue(WebLinks.parse("http://example.org").isPresent());
    assertFalse(WebLinks.parse("file:///etc/passwd").isPresent());
    assertFalse(WebLinks.parse("javascript:alert(1)").isPresent());
    assertFalse(WebLinks.parse("https://user:pass@example.org").isPresent());
    assertFalse(WebLinks.parse("not a url").isPresent());
    assertFalse(WebLinks.parse("/relative/path").isPresent());
  }
}
