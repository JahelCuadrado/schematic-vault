package com.einsteinmods.schematicvault.core.nbt;

import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.compound;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.integer;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.list;
import static com.einsteinmods.schematicvault.core.nbt.NbtFixtures.string;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ByteTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.CompoundTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.DoubleTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.FloatTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.IntArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongArrayTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.LongTag;
import com.einsteinmods.schematicvault.core.nbt.NbtTag.ShortTag;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NbtReaderTest {
  private static final CompoundTag EVERY_TYPE =
      compound(
          "byte", new ByteTag((byte) -3),
          "short", new ShortTag((short) -300),
          "int", integer(70_000),
          "long", new LongTag(-5_000_000_000L),
          "float", new FloatTag(1.5F),
          "double", new DoubleTag(-2.25),
          "bytes", new ByteArrayTag(new byte[] {1, -2, 3}),
          "text", string("schön €"),
          "list", list(integer(1), integer(2)),
          "nested", compound("name", string("minecraft:stone")),
          "ints", new IntArrayTag(new int[] {Integer.MIN_VALUE, 7}),
          "longs", new LongArrayTag(new long[] {Long.MAX_VALUE, -1}));

  @Test
  void readsEveryTagTypeUncompressedGzipAndZlib() throws NbtFormatException {
    for (NbtWriter.Compression compression : NbtWriter.Compression.values()) {
      CompoundTag read = NbtReader.read(NbtFixtures.write(EVERY_TYPE, compression));
      assertEquals(70_000, read.intValue("int", 0));
      assertEquals(-3, read.intValue("byte", 0));
      assertEquals(new LongTag(-5_000_000_000L), read.get("long"));
      assertEquals(new FloatTag(1.5F), read.get("float"));
      assertEquals(new DoubleTag(-2.25), read.get("double"));
      assertArrayEquals(new byte[] {1, -2, 3}, read.byteArray("bytes"));
      assertEquals("schön €", read.string("text").orElseThrow());
      assertEquals(2, read.list("list").size());
      assertEquals("minecraft:stone", read.compound("nested").orElseThrow().string("name").get());
      assertArrayEquals(new int[] {Integer.MIN_VALUE, 7}, read.intArray("ints"));
      assertArrayEquals(new long[] {Long.MAX_VALUE, -1}, read.longArray("longs"));
    }
  }

  @Test
  void refusesATruncatedFile() {
    byte[] file = NbtFixtures.write(EVERY_TYPE, NbtWriter.Compression.NONE);
    byte[] truncated = Arrays.copyOf(file, file.length - 5);
    assertThrows(NbtFormatException.class, () -> NbtReader.read(truncated));
  }

  @Test
  void refusesAnArrayLongerThanTheFile() {
    byte[] file =
        NbtFixtures.write(
            compound("bytes", new ByteArrayTag(new byte[] {1, 2, 3, 4})),
            NbtWriter.Compression.NONE);
    // Root type + empty name (3 bytes), then the entry: type, name "bytes" (2 + 5), length.
    int lengthOffset = 3 + 1 + 2 + 5;
    file[lengthOffset] = 0x7F;
    assertThrows(NbtFormatException.class, () -> NbtReader.read(file));
  }

  @Test
  void refusesNestingDeeperThanTheGame() {
    CompoundTag deep = compound();
    for (int depth = 0; depth <= NbtReader.MAX_DEPTH; depth++) {
      deep = compound("inner", deep);
    }
    byte[] file = NbtFixtures.write(deep, NbtWriter.Compression.NONE);
    assertThrows(NbtFormatException.class, () -> NbtReader.read(file));
  }

  @Test
  void refusesARootThatIsNotACompound() {
    assertThrows(NbtFormatException.class, () -> NbtReader.read(new byte[] {8, 0, 0, 0, 0}));
    assertThrows(NbtFormatException.class, () -> NbtReader.read(new byte[0]));
  }
}
