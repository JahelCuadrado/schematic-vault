package com.einsteinmods.schematicvault.core.catalog;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A design's primary file and PNG thumbnail addresses, kept small: the API names both by their
 * SHA-256 ({@code https://shard/v1/files/e2/e231...d4.litematic}, {@code
 * v1/images/e6/e6a0...3e.thumb96.png}), so an address of that pattern is stored as its host root
 * (one shared string per host, {@code ""} for a relative path), its digest as four longs and a
 * {@link Shape}, instead of a 150-character string per address (about 290 bytes less per design).
 * Any other address is kept as given (verbatim), so nothing that reads it changes; the getters
 * rebuild the same string the API gave.
 *
 * <p>The getters allocate the string on every call: per-frame code must keep what it got (as {@code
 * SchematicList}'s rows do) or use {@link #hasThumbnail}. Addresses are not checked here: the
 * readers ({@code ApiJson}, {@link CatalogCacheCodec}) pass only addresses {@code RemotePaths}
 * accepts.
 */
public final class DesignAddresses {
  /** {@link #fileShape} / {@link #thumbnailShape} of an absent address. */
  static final byte ABSENT = -2;

  /** {@link #fileShape} / {@link #thumbnailShape} of an address kept as given. */
  static final byte VERBATIM = -1;

  /** Longs of a SHA-256 digest. */
  static final int DIGEST_LONGS = 4;

  private static final String HTTPS = "https://";
  private static final String RELATIVE_ROOT = "";
  private static final String API_ROOT = "v1/";
  private static final String FILES_FOLDER = "files";
  private static final String IMAGES_FOLDER = "images";
  private static final char SEPARATOR = '/';
  private static final int HEX_PER_LONG = Long.SIZE / 4;
  private static final int DIGEST_HEX = HEX_PER_LONG * DIGEST_LONGS;
  private static final int PREFIX_HEX = 2;
  private static final int HEX_RADIX = 16;
  private static final int HEX_BITS = 4;
  private static final int HEX_LETTER_OFFSET = 10;
  private static final int HASH_PRIME = 31;
  private static final Shape[] SHAPES = Shape.values();

  /** A design with neither address. */
  public static final DesignAddresses NONE = of(null, null);

  /**
   * The content-addressed patterns stored compactly: {@code v1/<folder>/<2 hex>/<64 hex><suffix>}.
   * New values go at the end: the cache stores the ordinal.
   */
  enum Shape {
    LITEMATIC(FILES_FOLDER, ".litematic"),
    SCHEMATIC(FILES_FOLDER, ".schematic"),
    SCHEM(FILES_FOLDER, ".schem"),
    NBT(FILES_FOLDER, ".nbt"),
    THUMBNAIL_96(IMAGES_FOLDER, ".thumb96.png");

    private final String folder;
    private final String suffix;

    /** Characters after the host root. */
    private final int pathLength;

    Shape(String folder, String suffix) {
      this.folder = folder;
      this.suffix = suffix;
      this.pathLength =
          API_ROOT.length() + folder.length() + 1 + PREFIX_HEX + 1 + DIGEST_HEX + suffix.length();
    }
  }

  /** Host root when compact, the whole address when verbatim, null when absent. */
  private final @Nullable String fileText;

  private final long file0;
  private final long file1;
  private final long file2;
  private final long file3;
  private final byte fileShape;
  private final @Nullable String thumbnailText;
  private final long thumbnail0;
  private final long thumbnail1;
  private final long thumbnail2;
  private final long thumbnail3;
  private final byte thumbnailShape;

  private DesignAddresses(
      @Nullable String fileText,
      long[] fileDigest,
      byte fileShape,
      @Nullable String thumbnailText,
      long[] thumbnailDigest,
      byte thumbnailShape) {
    this.fileText = fileText;
    this.file0 = fileDigest[0];
    this.file1 = fileDigest[1];
    this.file2 = fileDigest[2];
    this.file3 = fileDigest[3];
    this.fileShape = fileShape;
    this.thumbnailText = thumbnailText;
    this.thumbnail0 = thumbnailDigest[0];
    this.thumbnail1 = thumbnailDigest[1];
    this.thumbnail2 = thumbnailDigest[2];
    this.thumbnail3 = thumbnailDigest[3];
    this.thumbnailShape = thumbnailShape;
  }

  /** Both addresses, compact when they follow the API's pattern; null when absent. */
  public static DesignAddresses of(
      @Nullable String primaryFileUrl, @Nullable String thumbnailPngUrl) {
    long[] fileDigest = new long[DIGEST_LONGS];
    long[] thumbnailDigest = new long[DIGEST_LONGS];
    byte fileShape = parse(primaryFileUrl, fileDigest);
    byte thumbnailShape = parse(thumbnailPngUrl, thumbnailDigest);
    return new DesignAddresses(
        textOf(primaryFileUrl, fileShape),
        fileDigest,
        fileShape,
        textOf(thumbnailPngUrl, thumbnailShape),
        thumbnailDigest,
        thumbnailShape);
  }

  /**
   * From the compact index's names ({@code <64 hex><suffix>}, such as {@code e231...d4.litematic}
   * and {@code e6a0...3e.thumb96.png}): the primary file under {@code v1/files/}, the thumbnail
   * under {@code v1/images/}, each after its host root, with no address string built. Null when a
   * given name follows no {@link Shape} (the caller then builds its address for {@link #of}).
   *
   * @param fileRoot the file's host root ({@code https://shard/}, or {@code ""} for a path)
   * @param fileName the primary file's name, or null when there is none
   * @param thumbnailRoot the thumbnail's host root, as {@code fileRoot}
   * @param thumbnailName the thumbnail's name, or null when there is none
   */
  public static @Nullable DesignAddresses ofNames(
      String fileRoot,
      @Nullable String fileName,
      String thumbnailRoot,
      @Nullable String thumbnailName) {
    long[] fileDigest = new long[DIGEST_LONGS];
    long[] thumbnailDigest = new long[DIGEST_LONGS];
    byte fileShape = parseName(fileName, FILES_FOLDER, fileDigest);
    byte thumbnailShape = parseName(thumbnailName, IMAGES_FOLDER, thumbnailDigest);
    if (fileShape == VERBATIM || thumbnailShape == VERBATIM) {
      return null;
    }
    return new DesignAddresses(
        fileShape == ABSENT ? null : fileRoot,
        fileDigest,
        fileShape,
        thumbnailShape == ABSENT ? null : thumbnailRoot,
        thumbnailDigest,
        thumbnailShape);
  }

  /**
   * From stored parts ({@link CatalogCacheCodec}): per address a text as {@link #fileText} keeps
   * it, its digest (zeros unless compact) and a shape code ({@link #isShapeCode}), already checked
   * by the caller, which also shares each host root's string (its own string table).
   */
  static DesignAddresses ofParts(
      @Nullable String fileText,
      long[] fileDigest,
      byte fileShape,
      @Nullable String thumbnailText,
      long[] thumbnailDigest,
      byte thumbnailShape) {
    return new DesignAddresses(
        fileText, fileDigest, fileShape, thumbnailText, thumbnailDigest, thumbnailShape);
  }

  /** Whether {@code code} is {@link #ABSENT}, {@link #VERBATIM} or a {@link Shape} ordinal. */
  static boolean isShapeCode(byte code) {
    return code == ABSENT || code == VERBATIM || (code >= 0 && code < SHAPES.length);
  }

  /** The primary file's address, built on each call; null when the API gives none. */
  public @Nullable String primaryFileUrl() {
    return build(fileText, fileShape, file0, file1, file2, file3);
  }

  /** The 96 px PNG thumbnail's address, built on each call; null when the API has none. */
  public @Nullable String thumbnailPngUrl() {
    return build(thumbnailText, thumbnailShape, thumbnail0, thumbnail1, thumbnail2, thumbnail3);
  }

  /** Whether there is a thumbnail, without building its address. */
  public boolean hasThumbnail() {
    return thumbnailShape != ABSENT;
  }

  /**
   * The lowercase SHA-256 the primary file is named by, from the stored digest; null when the
   * address is absent or kept verbatim (then read it from {@link #primaryFileUrl}).
   */
  public @Nullable String primaryFileDigest() {
    return fileShape < 0 ? null : hex(file0, file1, file2, file3);
  }

  byte fileShape() {
    return fileShape;
  }

  @Nullable String fileText() {
    return fileText;
  }

  /** Part {@code part} (0 to 3) of the primary file's digest; 0 unless compact. */
  long fileDigest(int part) {
    return pick(part, file0, file1, file2, file3);
  }

  byte thumbnailShape() {
    return thumbnailShape;
  }

  @Nullable String thumbnailText() {
    return thumbnailText;
  }

  /** Part {@code part} (0 to 3) of the thumbnail's digest; 0 unless compact. */
  long thumbnailDigest(int part) {
    return pick(part, thumbnail0, thumbnail1, thumbnail2, thumbnail3);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DesignAddresses that
        && fileShape == that.fileShape
        && file0 == that.file0
        && file1 == that.file1
        && file2 == that.file2
        && file3 == that.file3
        && thumbnailShape == that.thumbnailShape
        && thumbnail0 == that.thumbnail0
        && thumbnail1 == that.thumbnail1
        && thumbnail2 == that.thumbnail2
        && thumbnail3 == that.thumbnail3
        && Objects.equals(fileText, that.fileText)
        && Objects.equals(thumbnailText, that.thumbnailText);
  }

  @Override
  public int hashCode() {
    int hash = Objects.hash(fileText, thumbnailText, fileShape, thumbnailShape);
    hash = hash * HASH_PRIME + Long.hashCode(file0 ^ file1 ^ file2 ^ file3);
    return hash * HASH_PRIME + Long.hashCode(thumbnail0 ^ thumbnail1 ^ thumbnail2 ^ thumbnail3);
  }

  @Override
  public String toString() {
    return "DesignAddresses[" + primaryFileUrl() + ", " + thumbnailPngUrl() + "]";
  }

  /**
   * The shape code of {@code address}, filling {@code digest} when it is compact (zeros otherwise,
   * so equal addresses always compare equal): a host root of {@code https://} and no other slash,
   * or none, then exactly one {@link Shape}'s path with a lowercase digest whose first two digits
   * name its folder.
   */
  private static byte parse(@Nullable String address, long[] digest) {
    if (address == null) {
      return ABSENT;
    }
    int pathStart = 0;
    if (address.startsWith(HTTPS)) {
      pathStart = address.indexOf(SEPARATOR, HTTPS.length()) + 1;
      if (pathStart <= HTTPS.length()) {
        return VERBATIM;
      }
    }
    int pathLength = address.length() - pathStart;
    for (Shape shape : SHAPES) {
      if (shape.pathLength == pathLength && matches(address, pathStart, shape, digest)) {
        return (byte) shape.ordinal();
      }
    }
    Arrays.fill(digest, 0);
    return VERBATIM;
  }

  /**
   * The shape code of an index name in {@code folder}, filling {@code digest} when compact: {@link
   * #ABSENT} for null, {@link #VERBATIM} when no shape of that folder matches.
   */
  private static byte parseName(@Nullable String name, String folder, long[] digest) {
    if (name == null) {
      return ABSENT;
    }
    for (Shape shape : SHAPES) {
      if (shape.folder.equals(folder)
          && name.length() == DIGEST_HEX + shape.suffix.length()
          && name.startsWith(shape.suffix, DIGEST_HEX)
          && readDigest(name, 0, digest)) {
        return (byte) shape.ordinal();
      }
    }
    Arrays.fill(digest, 0);
    return VERBATIM;
  }

  private static boolean matches(String address, int start, Shape shape, long[] digest) {
    int position = start;
    if (!address.startsWith(API_ROOT, position)) {
      return false;
    }
    position += API_ROOT.length();
    if (!address.startsWith(shape.folder, position)) {
      return false;
    }
    position += shape.folder.length();
    if (address.charAt(position) != SEPARATOR) {
      return false;
    }
    int prefix = position + 1;
    int digestStart = prefix + PREFIX_HEX + 1;
    if (address.charAt(prefix + PREFIX_HEX) != SEPARATOR
        || !address.regionMatches(prefix, address, digestStart, PREFIX_HEX)
        || !address.startsWith(shape.suffix, digestStart + DIGEST_HEX)) {
      return false;
    }
    return readDigest(address, digestStart, digest);
  }

  /** Reads {@link #DIGEST_HEX} lowercase hex digits at {@code start} into {@code digest}. */
  private static boolean readDigest(String text, int start, long[] digest) {
    for (int part = 0; part < DIGEST_LONGS; part++) {
      long value = 0;
      int partStart = start + part * HEX_PER_LONG;
      for (int index = partStart; index < partStart + HEX_PER_LONG; index++) {
        int digit = lowercaseHexDigit(text.charAt(index));
        if (digit < 0) {
          return false;
        }
        value = (value << HEX_BITS) | digit;
      }
      digest[part] = value;
    }
    return true;
  }

  private static int lowercaseHexDigit(char character) {
    if (character >= '0' && character <= '9') {
      return character - '0';
    }
    if (character >= 'a' && character <= 'f') {
      return character - 'a' + HEX_LETTER_OFFSET;
    }
    return -1;
  }

  /**
   * What {@link #fileText} keeps for {@code address} of {@code shape}. A host root is shared
   * through the JVM's string table (no table of ours): 60,000 designs hold the API's 32 shard roots
   * once.
   */
  private static @Nullable String textOf(@Nullable String address, byte shape) {
    if (shape == ABSENT || shape == VERBATIM) {
      return address;
    }
    int pathStart = address.length() - SHAPES[shape].pathLength;
    return pathStart == 0 ? RELATIVE_ROOT : address.substring(0, pathStart).intern();
  }

  private static @Nullable String build(
      @Nullable String text, byte shape, long part0, long part1, long part2, long part3) {
    if (shape == ABSENT || shape == VERBATIM) {
      return text;
    }
    Shape pattern = SHAPES[shape];
    String digest = hex(part0, part1, part2, part3);
    return new StringBuilder(text.length() + pattern.pathLength)
        .append(text)
        .append(API_ROOT)
        .append(pattern.folder)
        .append(SEPARATOR)
        .append(digest, 0, PREFIX_HEX)
        .append(SEPARATOR)
        .append(digest)
        .append(pattern.suffix)
        .toString();
  }

  private static String hex(long part0, long part1, long part2, long part3) {
    char[] digits = new char[DIGEST_HEX];
    writeHex(digits, 0, part0);
    writeHex(digits, HEX_PER_LONG, part1);
    writeHex(digits, HEX_PER_LONG * 2, part2);
    writeHex(digits, HEX_PER_LONG * 3, part3);
    return new String(digits);
  }

  private static void writeHex(char[] digits, int start, long value) {
    for (int index = HEX_PER_LONG - 1; index >= 0; index--) {
      digits[start + index] =
          Character.forDigit(
              (int) (value >>> ((HEX_PER_LONG - 1 - index) * HEX_BITS)) & (HEX_RADIX - 1),
              HEX_RADIX);
    }
  }

  private static long pick(int part, long part0, long part1, long part2, long part3) {
    return switch (part) {
      case 0 -> part0;
      case 1 -> part1;
      case 2 -> part2;
      case 3 -> part3;
      default -> throw new IndexOutOfBoundsException(part);
    };
  }
}
