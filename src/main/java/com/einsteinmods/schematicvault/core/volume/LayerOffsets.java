package com.einsteinmods.schematicvault.core.volume;

import java.util.Arrays;

/**
 * Where each Y layer of a design starts in a list ordered by Y, bottom first (a mesh's quads, its
 * block entities): the items of a {@link LayerRange} are then the run from {@link #start
 * start(bottom)} up to {@link #end end(top + 1)}, so showing a range of layers draws one run.
 * Immutable; {@code layers + 1} ints (4 bytes per layer).
 */
public final class LayerOffsets {
  private static final int KEY_BITS = 32;
  private static final long INDEX_MASK = 0xFFFF_FFFFL;

  /** {@code starts[layer]}: items below that layer; {@code starts[layers]}: all items. */
  private final int[] starts;

  private LayerOffsets(int[] starts) {
    this.starts = starts;
  }

  /** Collects the starts of a list of {@code layers} Y layers while it is written, bottom first. */
  public static Builder builder(int layers) {
    if (layers < 0) {
      throw new IllegalArgumentException("negative layer count " + layers);
    }
    return new Builder(layers);
  }

  /** Y layers covered. */
  public int layers() {
    return starts.length - 1;
  }

  /** Items in all layers. */
  public int total() {
    return starts[layers()];
  }

  /** First item of {@code layer} (clamped to 0 and {@link #layers}). */
  public int start(int layer) {
    return starts[Math.clamp(layer, 0, layers())];
  }

  /** Items of the bottom {@code shownLayers} layers, clamped to 0 and {@link #layers}. */
  public int end(int shownLayers) {
    return start(shownLayers);
  }

  /**
   * The item order with each layer's items kept together, layers bottom first, and inside a layer
   * by {@code keys} from the largest down (for translucent quads: the farthest first, as {@code
   * VertexSorting#byDistance} sorts them). The items of any run of layers are then still one run.
   *
   * @param keys one per item ({@link #total} of them)
   */
  public int[] orderWithinLayers(float[] keys) {
    if (keys.length != total()) {
      throw new IllegalArgumentException(keys.length + " keys for " + total() + " items");
    }
    long[] packed = new long[keys.length];
    for (int item = 0; item < keys.length; item++) {
      packed[item] = ((long) sortableBits(keys[item]) << KEY_BITS) | (item & INDEX_MASK);
    }
    int[] order = new int[keys.length];
    for (int layer = 0; layer < layers(); layer++) {
      int from = starts[layer];
      int to = starts[layer + 1];
      Arrays.sort(packed, from, to);
      for (int position = from; position < to; position++) {
        order[position] = (int) (packed[to - 1 - (position - from)] & INDEX_MASK);
      }
    }
    return order;
  }

  /** A float's bits as an int with the same order as the float (signed compare). */
  private static int sortableBits(float value) {
    int bits = Float.floatToIntBits(value);
    return bits ^ ((bits >> (Integer.SIZE - 1)) & Integer.MAX_VALUE);
  }

  /** Fills the starts while the list is written in Y order; single use, one thread. */
  public static final class Builder {
    private final int[] starts;
    private int nextLayer;
    private int lastCount;

    private Builder(int layers) {
      this.starts = new int[layers + 1];
    }

    /**
     * The list reached {@code layer} with {@code itemsBefore} items written: layers skipped since
     * the last call (empty) start there too. Layers must come in ascending order.
     */
    public void enter(int layer, int itemsBefore) {
      if (layer < nextLayer - 1 || layer >= starts.length - 1) {
        throw new IllegalArgumentException("layer " + layer + " out of order");
      }
      if (itemsBefore < lastCount) {
        throw new IllegalArgumentException("item count went down to " + itemsBefore);
      }
      for (; nextLayer <= layer; nextLayer++) {
        starts[nextLayer] = itemsBefore;
      }
      lastCount = itemsBefore;
    }

    /** The offsets once {@code total} items were written: layers never entered end empty. */
    public LayerOffsets finish(int total) {
      if (total < lastCount) {
        throw new IllegalArgumentException("total " + total + " below " + lastCount);
      }
      for (; nextLayer < starts.length; nextLayer++) {
        starts[nextLayer] = total;
      }
      return new LayerOffsets(starts.clone());
    }
  }
}
