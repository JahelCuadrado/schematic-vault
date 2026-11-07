package com.einsteinmods.schematicvault.core.catalog;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The on-disk catalog cache: one binary file with only the fields the mod uses. It starts with a
 * magic number, {@link #FORMAT_VERSION} and a header (API address, revision, language, generation
 * time) readable without the rest ({@link #readHeader}); then a table of every distinct string,
 * which the body refers to by index, so repeated authors, tags, categories, licenses, dates and
 * host roots are stored, and loaded, once (each read string instance is shared by every design that
 * uses it); then the category tree, the locale's texts and the designs. Addresses named by SHA-256
 * are stored as {@link DesignAddresses} keeps them: host root, four longs and a shape.
 *
 * <p>Bump {@link #FORMAT_VERSION} when the shape changes: an older file (or the JSON cache of
 * versions up to 6) is then no cache and is downloaded again. Every count and length is capped
 * before anything is allocated, and the writer enforces the same caps, so a corrupt or cut file
 * fails with {@link CatalogFormatException} instead of a huge allocation, and a written file is
 * always readable. API addresses are checked again on read, so an edited cache cannot send requests
 * elsewhere.
 *
 * <p>A cache belongs to the API address it was downloaded from: read for another address (the
 * player changed it in the settings) it is no cache, so one API's designs never show for another.
 */
public final class CatalogCacheCodec {
  public static final int FORMAT_VERSION = 7;

  /** {@code SVCC}: tells a cache from any other file (the old JSON caches start with '{'). */
  static final int MAGIC = 0x5356_4343;

  /** Longest string, in UTF-8 bytes; titles and names are far below. */
  static final int MAX_STRING_BYTES = 1 << 16;

  /** Most distinct strings: about 5 per design today. */
  static final int MAX_STRINGS = 1 << 23;

  /** Most designs, and most entries in one locale map. */
  static final int MAX_DESIGNS = 1 << 22;

  /** Most authors or tags of one design, and most children of one category. */
  static final int MAX_LIST = 1 << 12;

  /** Deepest category tree (the API's has 4 levels); stops a crafted file overflowing the stack. */
  static final int MAX_DEPTH = 32;

  /** Reference of a null string; others are their table index plus one. */
  private static final int NULL_REFERENCE = 0;

  /** Preallocation cap: a large count from a bad file only grows lists as data really arrives. */
  private static final int INITIAL_CAPACITY = 1 << 12;

  private static final int VERIFIED = 1;
  private static final int DESCRIBED = 1 << 1;
  private static final int HAS_RATES = 1 << 2;
  private static final int KNOWN_FLAGS = VERIFIED | DESCRIBED | HAS_RATES;

  /** Root check of one table entry: not checked yet, safe, unsafe. */
  private static final byte ROOT_UNCHECKED = 0;

  private static final byte ROOT_SAFE = 1;
  private static final byte ROOT_UNSAFE = 2;

  private CatalogCacheCodec() {}

  /**
   * The first fields of a cache, read alone so a check of the revision does not load the catalog.
   *
   * @param apiBaseUrl the API address the cache was written for
   */
  public record Header(String apiBaseUrl, String revision, String language, String generatedAt) {}

  /**
   * @param apiBaseUrl the API address the catalog came from
   */
  public static void write(Catalog catalog, String apiBaseUrl, OutputStream output)
      throws IOException {
    Strings strings = new Strings();
    CacheOutput body = new CacheOutput();
    writeNodes(body, strings, catalog.taxonomy(), 0);
    LocaleTexts locale = catalog.locale();
    writeNames(body, strings, locale.categoryNames());
    writeNames(body, strings, locale.titles());
    writeNames(body, strings, locale.tagNames());
    List<SchematicSummary> schematics = catalog.schematics();
    writeCount(body, schematics.size(), MAX_DESIGNS);
    for (SchematicSummary schematic : schematics) {
      writeSchematic(body, strings, schematic);
    }
    CacheOutput head = new CacheOutput();
    head.writeInt(MAGIC);
    head.writeInt(FORMAT_VERSION);
    head.writeString(apiBaseUrl, MAX_STRING_BYTES);
    head.writeString(catalog.revision(), MAX_STRING_BYTES);
    head.writeString(locale.language(), MAX_STRING_BYTES);
    head.writeString(catalog.generatedAt(), MAX_STRING_BYTES);
    writeCount(head, strings.values.size(), MAX_STRINGS);
    for (String value : strings.values) {
      head.writeString(value, MAX_STRING_BYTES);
    }
    head.writeTo(output);
    body.writeTo(output);
    output.flush();
  }

  /**
   * Reads only the header: a few hundred bytes, whatever the catalog's size.
   *
   * @throws CatalogFormatException when the file is not a cache of this format version
   */
  public static Header readHeader(InputStream input) throws IOException, CatalogFormatException {
    try {
      return readHeader(new CacheInput(input));
    } catch (EOFException exception) {
      throw new CatalogFormatException("truncated catalog cache", exception);
    }
  }

  /**
   * @param apiBaseUrl the API address in use: a cache of another one is no cache
   * @throws CatalogFormatException when the file is not a whole cache of this format version and
   *     address
   */
  public static Catalog read(InputStream input, String apiBaseUrl)
      throws IOException, CatalogFormatException {
    try {
      CacheInput reader = new CacheInput(input);
      Header header = readHeader(reader);
      if (!apiBaseUrl.equals(header.apiBaseUrl())) {
        throw new CatalogFormatException("catalog cache of another API address");
      }
      String[] strings = readStrings(reader);
      Body body = new Body(reader, strings);
      List<CategoryNode> taxonomy = body.nodes(0);
      Map<String, String> categoryNames = body.names();
      Map<String, String> titles = body.names();
      Map<String, String> tagNames = body.names();
      int count = reader.readCount(MAX_DESIGNS);
      List<SchematicSummary> schematics = new ArrayList<>(Math.min(count, INITIAL_CAPACITY));
      for (int index = 0; index < count; index++) {
        schematics.add(body.schematic());
      }
      return new Catalog(
          header.revision(),
          header.generatedAt(),
          schematics,
          taxonomy,
          new LocaleTexts(header.language(), categoryNames, titles, tagNames));
    } catch (EOFException exception) {
      throw new CatalogFormatException("truncated catalog cache", exception);
    }
  }

  private static Header readHeader(CacheInput reader) throws IOException, CatalogFormatException {
    if (reader.readInt() != MAGIC || reader.readInt() != FORMAT_VERSION) {
      throw new CatalogFormatException("catalog cache of another format");
    }
    return new Header(
        reader.readString(MAX_STRING_BYTES),
        reader.readString(MAX_STRING_BYTES),
        reader.readString(MAX_STRING_BYTES),
        reader.readString(MAX_STRING_BYTES));
  }

  private static String[] readStrings(CacheInput reader)
      throws IOException, CatalogFormatException {
    int count = reader.readCount(MAX_STRINGS);
    List<String> strings = new ArrayList<>(Math.min(count, INITIAL_CAPACITY));
    for (int index = 0; index < count; index++) {
      strings.add(reader.readString(MAX_STRING_BYTES));
    }
    return strings.toArray(String[]::new);
  }

  private static void writeCount(CacheOutput output, int count, int max) throws IOException {
    if (count > max) {
      throw new IOException("count " + count + " over " + max + ": too large for the cache");
    }
    output.writeVarInt(count);
  }

  private static void writeNodes(
      CacheOutput output, Strings strings, List<CategoryNode> nodes, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("category tree deeper than " + MAX_DEPTH + ": too deep for the cache");
    }
    writeCount(output, nodes.size(), MAX_LIST);
    for (CategoryNode node : nodes) {
      strings.write(output, node.path());
      strings.write(output, node.name());
      output.writeSigned(node.count());
      writeNodes(output, strings, node.children(), depth + 1);
    }
  }

  private static void writeNames(CacheOutput output, Strings strings, Map<String, String> names)
      throws IOException {
    writeCount(output, names.size(), MAX_DESIGNS);
    for (Map.Entry<String, String> entry : names.entrySet()) {
      strings.write(output, entry.getKey());
      strings.write(output, entry.getValue());
    }
  }

  private static void writeSchematic(
      CacheOutput output, Strings strings, SchematicSummary schematic) throws IOException {
    strings.write(output, schematic.id());
    strings.write(output, schematic.title());
    writeList(output, strings, schematic.authors());
    writeList(output, strings, schematic.tags());
    strings.write(output, schematic.category());
    strings.write(output, schematic.license());
    strings.write(output, schematic.sourceId());
    strings.write(output, schematic.minecraftVersion());
    BlockSize size = schematic.size();
    output.writeSigned(size.x());
    output.writeSigned(size.y());
    output.writeSigned(size.z());
    output.writeSigned(schematic.blockCount());
    Difficulty difficulty = schematic.difficulty();
    strings.write(output, difficulty == null ? null : difficulty.id());
    output.writeByte(
        (schematic.verified() ? VERIFIED : 0)
            | (schematic.described() ? DESCRIBED : 0)
            | (schematic.hasRates() ? HAS_RATES : 0));
    output.writeSigned(schematic.width());
    DesignAddresses addresses = schematic.addresses();
    writeAddress(output, strings, addresses.fileShape(), addresses.fileText(), addresses, true);
    writeAddress(
        output, strings, addresses.thumbnailShape(), addresses.thumbnailText(), addresses, false);
    strings.write(output, schematic.updatedAt());
    ReviewStatus status = schematic.status();
    strings.write(output, status == null ? null : status.id());
    output.writeSigned(schematic.dataVersion());
    output.writeSigned(schematic.popularity());
    SourceStatus sourceStatus = schematic.sourceStatus();
    strings.write(output, sourceStatus == null ? null : sourceStatus.id());
    output.writeSigned(schematic.endorsementCount());
  }

  /** Shape code, then the text (root or whole address) unless absent, then a compact digest. */
  private static void writeAddress(
      CacheOutput output,
      Strings strings,
      byte shape,
      @Nullable String text,
      DesignAddresses addresses,
      boolean file)
      throws IOException {
    output.writeByte(shape);
    if (shape == DesignAddresses.ABSENT) {
      return;
    }
    strings.write(output, text);
    if (shape == DesignAddresses.VERBATIM) {
      return;
    }
    for (int part = 0; part < DesignAddresses.DIGEST_LONGS; part++) {
      output.writeLong(file ? addresses.fileDigest(part) : addresses.thumbnailDigest(part));
    }
  }

  private static void writeList(CacheOutput output, Strings strings, List<String> values)
      throws IOException {
    writeCount(output, values.size(), MAX_LIST);
    for (String value : values) {
      strings.write(output, value);
    }
  }

  /** The string table being written: each distinct string once, in first-use order. */
  private static final class Strings {
    private final Map<String, Integer> references = new HashMap<>();
    private final List<String> values = new ArrayList<>();

    void write(CacheOutput output, @Nullable String value) throws IOException {
      if (value == null) {
        output.writeVarInt(NULL_REFERENCE);
        return;
      }
      Integer reference = references.get(value);
      if (reference == null) {
        if (values.size() >= MAX_STRINGS) {
          throw new IOException("more than " + MAX_STRINGS + " strings: too many for the cache");
        }
        values.add(value);
        reference = values.size();
        references.put(value, reference);
      }
      output.writeVarInt(reference);
    }
  }

  /** Reads the body against the string table. */
  private static final class Body {
    private final CacheInput reader;
    private final String[] strings;
    private final byte[] rootChecks;
    private final long[] fileDigest = new long[DesignAddresses.DIGEST_LONGS];
    private final long[] thumbnailDigest = new long[DesignAddresses.DIGEST_LONGS];
    private @Nullable String addressText;

    Body(CacheInput reader, String[] strings) {
      this.reader = reader;
      this.strings = strings;
      this.rootChecks = new byte[strings.length];
    }

    List<CategoryNode> nodes(int depth) throws IOException, CatalogFormatException {
      if (depth > MAX_DEPTH) {
        throw new CatalogFormatException("category tree too deep in the catalog cache");
      }
      int count = reader.readCount(MAX_LIST);
      List<CategoryNode> nodes = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        String path = string();
        String name = optional();
        int designs = reader.readSignedInt();
        nodes.add(new CategoryNode(path, name == null ? path : name, designs, nodes(depth + 1)));
      }
      return nodes;
    }

    Map<String, String> names() throws IOException, CatalogFormatException {
      int count = reader.readCount(MAX_DESIGNS);
      Map<String, String> names = HashMap.newHashMap(Math.min(count, INITIAL_CAPACITY));
      for (int index = 0; index < count; index++) {
        names.put(string(), string());
      }
      return names;
    }

    SchematicSummary schematic() throws IOException, CatalogFormatException {
      String id = string();
      String title = string();
      List<String> authors = list();
      List<String> tags = list();
      String category = string();
      String license = optional();
      String sourceId = string();
      String minecraftVersion = optional();
      BlockSize size =
          new BlockSize(reader.readSignedInt(), reader.readSignedInt(), reader.readSignedInt());
      long blockCount = reader.readSigned();
      String difficulty = optional();
      int flags = reader.readByte();
      if ((flags & ~KNOWN_FLAGS) != 0) {
        throw new CatalogFormatException("unknown flags in the catalog cache");
      }
      int width = reader.readSignedInt();
      byte fileShape = address(fileDigest);
      String fileText = addressText;
      byte thumbnailShape = address(thumbnailDigest);
      String thumbnailText = addressText;
      String updatedAt = string();
      String status = optional();
      int dataVersion = reader.readSignedInt();
      int popularity = reader.readSignedInt();
      String sourceStatus = optional();
      int endorsementCount = reader.readSignedInt();
      return new SchematicSummary(
          id,
          title,
          authors,
          tags,
          category,
          license,
          sourceId,
          minecraftVersion,
          size,
          blockCount,
          difficulty == null ? null : Difficulty.fromId(difficulty).orElse(null),
          (flags & VERIFIED) != 0,
          width,
          DesignAddresses.ofParts(
              fileText, fileDigest, fileShape, thumbnailText, thumbnailDigest, thumbnailShape),
          updatedAt,
          status == null ? null : ReviewStatus.fromId(status).orElse(null),
          dataVersion,
          (flags & DESCRIBED) != 0,
          popularity,
          sourceStatus == null ? null : SourceStatus.fromId(sourceStatus).orElse(null),
          endorsementCount,
          (flags & HAS_RATES) != 0);
    }

    /**
     * One address into {@link #addressText} and {@code digest}; its shape code. An address that is
     * no longer safe (an edited file) reads as absent, as the API reader does.
     */
    private byte address(long[] digest) throws IOException, CatalogFormatException {
      Arrays.fill(digest, 0);
      addressText = null;
      byte shape = (byte) reader.readByte();
      if (!DesignAddresses.isShapeCode(shape)) {
        throw new CatalogFormatException("unknown address shape in the catalog cache");
      }
      if (shape == DesignAddresses.ABSENT) {
        return shape;
      }
      int reference = reader.readCount(strings.length);
      if (reference == NULL_REFERENCE) {
        throw new CatalogFormatException("address without text in the catalog cache");
      }
      String text = strings[reference - 1];
      if (shape == DesignAddresses.VERBATIM) {
        if (!RemotePaths.isSafeAddress(text)) {
          return DesignAddresses.ABSENT;
        }
        addressText = text;
        return shape;
      }
      for (int part = 0; part < DesignAddresses.DIGEST_LONGS; part++) {
        digest[part] = reader.readLong();
      }
      if (!isSafeRoot(reference - 1)) {
        Arrays.fill(digest, 0);
        return DesignAddresses.ABSENT;
      }
      addressText = text;
      return shape;
    }

    /** {@code ""} (relative) or a plain {@code https://host/}, checked once per table entry. */
    private boolean isSafeRoot(int index) {
      if (rootChecks[index] == ROOT_UNCHECKED) {
        String root = strings[index];
        boolean safe = root.isEmpty() || RemotePaths.hostRoot(root) != null;
        rootChecks[index] = safe ? ROOT_SAFE : ROOT_UNSAFE;
      }
      return rootChecks[index] == ROOT_SAFE;
    }

    private List<String> list() throws IOException, CatalogFormatException {
      int count = reader.readCount(MAX_LIST);
      String[] values = new String[count];
      for (int index = 0; index < count; index++) {
        values[index] = string();
      }
      return List.of(values);
    }

    private String string() throws IOException, CatalogFormatException {
      String value = optional();
      if (value == null) {
        throw new CatalogFormatException("missing text in the catalog cache");
      }
      return value;
    }

    private @Nullable String optional() throws IOException, CatalogFormatException {
      int reference = reader.readCount(strings.length);
      return reference == NULL_REFERENCE ? null : strings[reference - 1];
    }
  }
}
