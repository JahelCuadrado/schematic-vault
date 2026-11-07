package com.einsteinmods.schematicvault.core.volume;

/**
 * A run of a design's Y layers, both ends kept: layers are counted from 0 at the design's bottom,
 * so a single layer has {@code bottom == top}. {@link #ALL} means every layer whatever the height;
 * {@link #clampedTo} gives the exact range of a design.
 *
 * @param bottom the lowest layer kept
 * @param top the highest layer kept, at least {@code bottom}
 */
public record LayerRange(int bottom, int top) {
  /** Every layer of any design. */
  public static final LayerRange ALL = new LayerRange(0, Integer.MAX_VALUE);

  public LayerRange {
    if (bottom < 0 || top < bottom) {
      throw new IllegalArgumentException("bad layer range " + bottom + "-" + top);
    }
  }

  /** Whether this range holds every layer of a design of {@code layerCount} layers. */
  public boolean covers(int layerCount) {
    return bottom == 0 && top >= layerCount - 1;
  }

  /** Whether {@code layer} is one of the range's. */
  public boolean contains(int layer) {
    return layer >= bottom && layer <= top;
  }

  /** Whether the two ranges share a layer. */
  public boolean overlaps(LayerRange other) {
    return bottom <= other.top && other.bottom <= top;
  }

  /** Whether every layer of {@code other} is one of the range's. */
  public boolean contains(LayerRange other) {
    return bottom <= other.bottom && other.top <= top;
  }

  /** Whether the two ends meet: one layer is kept. */
  public boolean isSingleLayer() {
    return bottom == top;
  }

  /**
   * This range inside a design of {@code layerCount} layers (a design has at least one): this same
   * instance when it already fits, so nothing is allocated then.
   */
  public LayerRange clampedTo(int layerCount) {
    int highest = Math.max(layerCount, 1) - 1;
    if (top <= highest) {
      return this;
    }
    return new LayerRange(Math.min(bottom, highest), highest);
  }
}
