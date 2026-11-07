package com.einsteinmods.schematicvault.core.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrowserPanelLayoutTest {
  private static final int PANEL_LEFT = 400;
  private static final int PANEL_HEIGHT = 310;

  @Test
  void theSlotStartsWhereLitematicasDataEnds() {
    BrowserPanelLayout layout =
        BrowserPanelLayout.of(PANEL_LEFT + 3, BrowserPanelLayout.PANEL_TOP + 150, PANEL_HEIGHT);

    assertEquals(PANEL_LEFT + 1, layout.coverLeft());
    assertEquals(PANEL_LEFT + BrowserPanelLayout.PANEL_WIDTH - 1, layout.coverRight());
    assertEquals(BrowserPanelLayout.PANEL_TOP + 150, layout.coverTop());
    assertEquals(PANEL_LEFT + 3, layout.slotLeft());
    assertEquals(BrowserPanelLayout.PANEL_TOP + 152, layout.slotTop());
    assertTrue(layout.showsPreview());
  }

  @Test
  void aFileWithoutDataTakesTheWholePanel() {
    BrowserPanelLayout layout =
        BrowserPanelLayout.of(PANEL_LEFT, BrowserPanelLayout.PANEL_TOP, PANEL_HEIGHT);

    assertEquals(PANEL_LEFT + 3, layout.slotLeft());
    assertEquals(BrowserPanelLayout.PANEL_TOP + 3, layout.coverTop());
  }

  @Test
  void theButtonSitsAlongThePanelsBottomWithTheSlotAboveIt() {
    BrowserPanelLayout layout =
        BrowserPanelLayout.of(PANEL_LEFT + 3, BrowserPanelLayout.PANEL_TOP + 150, PANEL_HEIGHT);

    int panelBottom = BrowserPanelLayout.PANEL_TOP + PANEL_HEIGHT;
    assertEquals(panelBottom - 3, layout.buttonTop() + BrowserPanelLayout.BUTTON_HEIGHT);
    assertTrue(layout.slotBottom() <= layout.buttonTop());
    assertTrue(layout.coverBottom() < panelBottom);
  }

  @Test
  void aShortWindowKeepsTheButtonButNotThe3d() {
    BrowserPanelLayout layout =
        BrowserPanelLayout.of(PANEL_LEFT + 3, BrowserPanelLayout.PANEL_TOP + 150, 190);

    assertFalse(layout.showsPreview());
    assertEquals(BrowserPanelLayout.PANEL_TOP + 190 - 23, layout.buttonTop());
  }
}
