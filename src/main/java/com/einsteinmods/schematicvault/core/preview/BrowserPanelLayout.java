package com.einsteinmods.schematicvault.core.preview;

/**
 * Where the preview goes in the info panel of Litematica's schematic browser: under the data it
 * shows, in the place of its preview image, with one button along the panel's bottom. The numbers
 * copy {@code WidgetSchematicBrowser#drawSelectedSchematicInfo} ({@code infoWidth} and the 3 pixels
 * it moves in once it has data to show) and {@code GuiSchematicLoad}'s constructor (the list's
 * top): re-check them on a Litematica bump.
 *
 * @param coverLeft the backdrop that hides Litematica's preview image; its right edge is {@code
 *     coverRight}
 * @param coverTop where Litematica's data ends: its preview image starts here too
 * @param slotLeft where the 3D or a message goes
 * @param buttonTop the button's top; it spans the slot's width and is {@link #BUTTON_HEIGHT} high
 */
public record BrowserPanelLayout(
    int coverLeft,
    int coverTop,
    int coverRight,
    int coverBottom,
    int slotLeft,
    int slotTop,
    int slotRight,
    int slotBottom,
    int buttonTop) {
  public static final int BUTTON_HEIGHT = 20;

  /** {@code GuiSchematicLoad}'s list top, where the panel starts. */
  static final int PANEL_TOP = 24;

  /** {@code WidgetSchematicBrowser#infoWidth}. */
  static final int PANEL_WIDTH = 170;

  /** What the panel's data is moved in by, and the margin kept here. */
  private static final int INSET = 3;

  /** The panel's outline. */
  private static final int BORDER = 1;

  private static final int GAP = 2;

  /** Below this the 3D is too small to tell a design by: only the button stays. */
  private static final int MIN_SLOT_HEIGHT = 40;

  /**
   * The layout for the values {@code drawSelectedSchematicInfo} ends with.
   *
   * @param x its {@code x}: the panel's left edge, {@value #INSET} further once it drew data
   * @param y its {@code y}: the panel's top until it draws data, then where that data ends
   * @param height the panel's height
   */
  public static BrowserPanelLayout of(int x, int y, int height) {
    boolean drewData = y > PANEL_TOP;
    int panelLeft = drewData ? x - INSET : x;
    int contentTop = drewData ? y : PANEL_TOP + INSET;
    int panelBottom = PANEL_TOP + height;
    int buttonTop = panelBottom - INSET - BUTTON_HEIGHT;
    return new BrowserPanelLayout(
        panelLeft + BORDER,
        contentTop,
        panelLeft + PANEL_WIDTH - BORDER,
        panelBottom - BORDER,
        panelLeft + INSET,
        contentTop + GAP,
        panelLeft + PANEL_WIDTH - INSET,
        buttonTop - GAP,
        buttonTop);
  }

  /** Whether the slot is tall enough to show the 3D; the button stays either way. */
  public boolean showsPreview() {
    return slotBottom - slotTop >= MIN_SLOT_HEIGHT;
  }
}
