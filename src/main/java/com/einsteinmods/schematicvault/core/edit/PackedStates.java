package com.einsteinmods.schematicvault.core.edit;

import com.einsteinmods.schematicvault.core.volume.VolumeFormatException;

/**
 * Litematica's packed palette indices ({@code BlockStates}): {@code max(2, ceil(log2(palette
 * size)))} bits per cell, low bits first, an entry may span two longs, exactly {@code ceil(cells *
 * bits / 64)} longs (Litematica's {@code LitematicaBitArray} refuses another length).
 */
final class PackedStates {
  private static final int MIN_BITS = 2;

  /** Bit index to long index: log2(64). */
  private static final int LONG_INDEX_SHIFT = Integer.numberOfTrailingZeros(Long.SIZE);

  private PackedStates() {}

  /** Bits per cell for a palette of {@code paletteSize} entries. */
  static int bits(int paletteSize) {
    return Math.max(MIN_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
  }

  /** Longs holding {@code cells} entries of {@code bits} bits. */
  static int longs(int cells, int bits) {
    return Math.toIntExact(((long) cells * bits + Long.SIZE - 1) >>> LONG_INDEX_SHIFT);
  }

  /** The {@code cells} indices packed in {@code packed}, each checked against the palette. */
  static int[] unpack(long[] packed, int cells, int paletteSize) throws VolumeFormatException {
    int bits = bits(paletteSize);
    if ((long) packed.length * Long.SIZE < (long) cells * bits) {
      throw VolumeFormatException.unreadable("block state array shorter than its region");
    }
    long mask = (1L << bits) - 1;
    int[] values = new int[cells];
    for (int index = 0; index < cells; index++) {
      long bitIndex = (long) index * bits;
      int word = (int) (bitIndex >>> LONG_INDEX_SHIFT);
      int shift = (int) (bitIndex & (Long.SIZE - 1));
      long value = packed[word] >>> shift;
      if (shift + bits > Long.SIZE) {
        value |= packed[word + 1] << (Long.SIZE - shift);
      }
      int local = (int) (value & mask);
      if (local >= paletteSize) {
        throw VolumeFormatException.unreadable("block state outside its palette");
      }
      values[index] = local;
    }
    return values;
  }

  /** {@code values} packed for a palette of {@code paletteSize} entries. */
  static long[] pack(int[] values, int paletteSize) {
    int bits = bits(paletteSize);
    long[] packed = new long[longs(values.length, bits)];
    for (int index = 0; index < values.length; index++) {
      long value = values[index];
      long bitIndex = (long) index * bits;
      int word = (int) (bitIndex >>> LONG_INDEX_SHIFT);
      int shift = (int) (bitIndex & (Long.SIZE - 1));
      packed[word] |= value << shift;
      if (shift + bits > Long.SIZE) {
        packed[word + 1] |= value >>> (Long.SIZE - shift);
      }
    }
    return packed;
  }
}
