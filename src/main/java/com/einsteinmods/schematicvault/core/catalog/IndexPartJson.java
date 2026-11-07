package com.einsteinmods.schematicvault.core.catalog;

import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.core.net.ShardRule;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads one part of the API's compact catalog index ({@code v1/schematics/index/{part}.json}):
 * {@code {part, partCount, total, fields, rows}}, each row an array positional in the order of
 * {@code fields}. Columns are found by name, so columns the API appends later are skipped and a
 * missing optional one reads as absent, with the same tolerance as {@link ApiJson#page}: a value of
 * another type reads as absent, and a row without an id or a title is left out.
 *
 * <p>Read as a stream from the body's bytes: neither the whole text as a string nor a JSON tree of
 * the part (about 8.5 MB of rows each) is ever held. Only when {@code rows} comes before {@code
 * fields} (the API writes them the other way) are the rows kept as a tree until the columns are
 * known.
 *
 * <p>{@code file} and {@code thumbnail} are names ({@code <sha256><extension>}): served at {@code
 * v1/files/<first 2 hex>/<name>} and {@code v1/images/<first 2 hex>/<name>} on the shard of the
 * digest's first byte ({@link ShardRule#shardOfByte}), as the pages' {@code primaryFileUrl} and
 * {@code thumbnailPngUrl} give them; they go straight into {@link DesignAddresses}.
 */
public final class IndexPartJson {
  private static final String PART = "part";
  private static final String PART_COUNT = "partCount";
  private static final String FIELDS = "fields";
  private static final String ROWS = "rows";
  private static final String SIZE_X = "x";
  private static final String SIZE_Y = "y";
  private static final String SIZE_Z = "z";
  private static final String API_ROOT = RemotePaths.API_ROOT;
  private static final String FILES_FOLDER = "files/";
  private static final String IMAGES_FOLDER = "images/";
  private static final char SEPARATOR = '/';
  private static final String RELATIVE_ROOT = "";
  private static final String NO_SOURCE = "";
  private static final int PREFIX_HEX = 2;
  private static final int HEX_RADIX = 16;
  private static final int HEX_LETTER_OFFSET = 10;
  private static final int NO_PART = -1;

  /** A row's columns, by their name in {@code fields}. */
  private enum Column {
    ID("id"),
    TITLE("title"),
    AUTHORS("authors"),
    TAGS("tags"),
    CATEGORY("category"),
    LICENSE("license"),
    SIZE("size"),
    BLOCK_COUNT("blockCount"),
    DIFFICULTY("difficulty"),
    VERIFIED("verified"),
    WIDTH("width"),
    UPDATED_AT("updatedAt"),
    STATUS("status"),
    DATA_VERSION_MAX("dataVersionMax"),
    HAS_SUMMARY("hasSummary"),
    POPULARITY("popularity"),
    SOURCE_STATUS("sourceStatus"),
    ENDORSEMENT_COUNT("endorsementCount"),
    HAS_RATES("hasRates"),
    FILE("file"),
    THUMBNAIL("thumbnail");

    private static final Map<String, Column> BY_NAME = byName();

    private final String fieldName;

    Column(String fieldName) {
      this.fieldName = fieldName;
    }

    private static Map<String, Column> byName() {
      Map<String, Column> columns = new HashMap<>();
      for (Column column : values()) {
        columns.put(column.fieldName, column);
      }
      return Map.copyOf(columns);
    }
  }

  private IndexPartJson() {}

  /**
   * The designs of index part {@code part}, in row order.
   *
   * @param json the part's body (UTF-8)
   * @param part the part asked for (from 1); a body that says another part, or another part count,
   *     fails (two revisions mixed)
   * @param partCount the manifest's {@code index.partCount}
   * @param shardRoots the manifest's shard roots ({@code https://host/}), empty without shards
   *     (addresses are then API paths)
   */
  public static List<SchematicSummary> read(
      byte[] json, int part, int partCount, List<String> shardRoots) throws CatalogFormatException {
    try (Reader input =
        new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
      return read(new JsonReader(input), part, partCount, shardRoots);
    } catch (IOException | IllegalStateException | JsonParseException exception) {
      // JsonReader reports malformed JSON as IOException and a wrong token as IllegalState.
      throw new CatalogFormatException("invalid index part " + part, exception);
    }
  }

  private static List<SchematicSummary> read(
      JsonReader reader, int part, int partCount, List<String> shardRoots)
      throws IOException, CatalogFormatException {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
      throw new CatalogFormatException("index part is not an object");
    }
    Rows rows = new Rows(shardRoots);
    int statedPart = NO_PART;
    int statedCount = NO_PART;
    @Nullable JsonElement rowsBeforeFields = null;
    boolean rowsSeen = false;
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case PART -> statedPart = integer(reader, NO_PART);
        case PART_COUNT -> statedCount = integer(reader, NO_PART);
        case FIELDS -> rows.columns = columns(reader);
        case ROWS -> {
          rowsSeen = true;
          if (rows.columns == null) {
            rowsBeforeFields = JsonParser.parseReader(reader);
          } else {
            rows.readAll(reader);
          }
        }
        default -> reader.skipValue();
      }
    }
    reader.endObject();
    if (statedPart != part || statedCount != partCount) {
      throw new CatalogFormatException(
          "index part " + statedPart + " of " + statedCount + " for " + part + " of " + partCount);
    }
    if (rows.columns == null || !rowsSeen) {
      throw new CatalogFormatException("index part without fields or rows");
    }
    if (rowsBeforeFields != null) {
      rows.readAll(new JsonReader(new StringReader(rowsBeforeFields.toString())));
    }
    return rows.schematics;
  }

  /** Each position's column, null for a name the mod does not read. */
  private static @Nullable Column[] columns(JsonReader reader) throws IOException {
    List<@Nullable Column> columns = new ArrayList<>();
    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
      reader.skipValue();
      return new Column[0];
    }
    reader.beginArray();
    while (reader.hasNext()) {
      String name = string(reader);
      columns.add(name == null ? null : Column.BY_NAME.get(name));
    }
    reader.endArray();
    return columns.toArray(Column[]::new);
  }

  /** The rows of one part and what reading one needs; its {@link Values} are reused per row. */
  private static final class Rows {
    private final List<String> shardRoots;
    private final List<SchematicSummary> schematics = new ArrayList<>();
    private final Values values = new Values();
    private @Nullable Column @Nullable [] columns;

    private Rows(List<String> shardRoots) {
      this.shardRoots = shardRoots;
    }

    private void readAll(JsonReader reader) throws IOException {
      if (reader.peek() != JsonToken.BEGIN_ARRAY) {
        reader.skipValue();
        return;
      }
      reader.beginArray();
      while (reader.hasNext()) {
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
          readRow(reader);
        } else {
          reader.skipValue();
        }
      }
      reader.endArray();
    }

    private void readRow(JsonReader reader) throws IOException {
      @Nullable Column[] known = columns;
      values.reset();
      reader.beginArray();
      int position = 0;
      while (reader.hasNext()) {
        Column column = known != null && position < known.length ? known[position] : null;
        position++;
        if (column == null) {
          reader.skipValue();
        } else {
          values.read(column, reader);
        }
      }
      reader.endArray();
      SchematicSummary schematic = values.summary(shardRoots);
      if (schematic != null) {
        schematics.add(schematic);
      }
    }
  }

  /** One row's values; absent ones keep the defaults {@link ApiJson#page} uses. */
  private static final class Values {
    private @Nullable String id;
    private @Nullable String title;
    private List<String> authors = List.of();
    private List<String> tags = List.of();
    private @Nullable String category;
    private @Nullable String license;
    private BlockSize size = BlockSize.UNKNOWN;
    private long blockCount;
    private @Nullable String difficulty;
    private boolean verified;
    private int width;
    private @Nullable String updatedAt;
    private @Nullable String status;
    private int dataVersion;
    private boolean described;
    private int popularity;
    private @Nullable String sourceStatus;
    private int endorsementCount;
    private boolean hasRates;
    private @Nullable String file;
    private @Nullable String thumbnail;

    private void reset() {
      id = null;
      title = null;
      authors = List.of();
      tags = List.of();
      category = null;
      license = null;
      size = BlockSize.UNKNOWN;
      blockCount = 0;
      difficulty = null;
      verified = false;
      width = 0;
      updatedAt = null;
      status = null;
      dataVersion = 0;
      described = false;
      popularity = SchematicSummary.NO_POPULARITY;
      sourceStatus = null;
      endorsementCount = 0;
      hasRates = false;
      file = null;
      thumbnail = null;
    }

    private void read(Column column, JsonReader reader) throws IOException {
      switch (column) {
        case ID -> id = string(reader);
        case TITLE -> title = string(reader);
        case AUTHORS -> authors = strings(reader);
        case TAGS -> tags = strings(reader);
        case CATEGORY -> category = string(reader);
        case LICENSE -> license = string(reader);
        case SIZE -> size = size(reader);
        case BLOCK_COUNT -> blockCount = number(reader, 0);
        case DIFFICULTY -> difficulty = string(reader);
        case VERIFIED -> verified = bool(reader);
        case WIDTH -> width = integer(reader, 0);
        case UPDATED_AT -> updatedAt = string(reader);
        case STATUS -> status = string(reader);
        case DATA_VERSION_MAX -> dataVersion = integer(reader, 0);
        case HAS_SUMMARY -> described = bool(reader);
        case POPULARITY -> popularity = integer(reader, SchematicSummary.NO_POPULARITY);
        case SOURCE_STATUS -> sourceStatus = string(reader);
        case ENDORSEMENT_COUNT -> endorsementCount = integer(reader, 0);
        case HAS_RATES -> hasRates = bool(reader);
        case FILE -> file = string(reader);
        case THUMBNAIL -> thumbnail = string(reader);
      }
    }

    /**
     * The design as {@link ApiJson#page} reads it, or null without an id or a title. The index has
     * no {@code sourceId} nor {@code minecraftVersion}: they read as empty and null.
     */
    private @Nullable SchematicSummary summary(List<String> shardRoots) {
      if (id == null || id.isBlank() || title == null) {
        return null;
      }
      return new SchematicSummary(
          id,
          title,
          authors,
          tags,
          category == null ? "" : category,
          license,
          NO_SOURCE,
          null,
          size,
          Math.max(0, blockCount),
          Difficulty.fromId(difficulty == null ? "" : difficulty).orElse(null),
          verified,
          Math.max(0, width),
          addresses(shardRoots),
          updatedAt == null ? "" : updatedAt,
          ReviewStatus.fromId(status == null ? "" : status).orElse(null),
          dataVersion,
          described,
          popularity,
          SourceStatus.fromId(sourceStatus == null ? "" : sourceStatus).orElse(null),
          endorsementCount,
          hasRates);
    }

    /**
     * Compact addresses without building a string; a name of another shape gets its address built
     * and checked ({@link RemotePaths}), and one that cannot be a safe address reads as absent.
     */
    private DesignAddresses addresses(List<String> shardRoots) {
      String fileRoot = rootOf(file, shardRoots);
      String thumbnailRoot = rootOf(thumbnail, shardRoots);
      String fileName = fileRoot == null ? null : file;
      String thumbnailName = thumbnailRoot == null ? null : thumbnail;
      DesignAddresses compact =
          DesignAddresses.ofNames(
              fileRoot == null ? RELATIVE_ROOT : fileRoot,
              fileName,
              thumbnailRoot == null ? RELATIVE_ROOT : thumbnailRoot,
              thumbnailName);
      return compact != null
          ? compact
          : DesignAddresses.of(
              address(fileRoot, FILES_FOLDER, fileName),
              address(thumbnailRoot, IMAGES_FOLDER, thumbnailName));
    }
  }

  /**
   * The host root of {@code name}: its first byte's shard, or {@code ""} without shards; null for
   * no name or one that does not start with two lowercase hex digits.
   */
  private static @Nullable String rootOf(@Nullable String name, List<String> shardRoots) {
    if (name == null || name.length() < PREFIX_HEX) {
      return null;
    }
    int high = lowercaseHexDigit(name.charAt(0));
    int low = lowercaseHexDigit(name.charAt(1));
    if (high < 0 || low < 0) {
      return null;
    }
    return shardRoots.isEmpty()
        ? RELATIVE_ROOT
        : shardRoots.get(ShardRule.shardOfByte(high * HEX_RADIX + low, shardRoots.size()));
  }

  private static @Nullable String address(
      @Nullable String root, String folder, @Nullable String name) {
    if (root == null || name == null) {
      return null;
    }
    String address = root + API_ROOT + folder + name.substring(0, PREFIX_HEX) + SEPARATOR + name;
    return RemotePaths.checkedAddress(address).orElse(null);
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

  private static @Nullable String string(JsonReader reader) throws IOException {
    if (reader.peek() == JsonToken.STRING) {
      return reader.nextString();
    }
    reader.skipValue();
    return null;
  }

  /** As {@code JsonFields#number}: a fraction is truncated, anything but a number is absent. */
  private static long number(JsonReader reader, long fallback) throws IOException {
    if (reader.peek() != JsonToken.NUMBER) {
      reader.skipValue();
      return fallback;
    }
    try {
      return reader.nextLong();
    } catch (NumberFormatException fraction) {
      return (long) reader.nextDouble();
    }
  }

  private static int integer(JsonReader reader, int fallback) throws IOException {
    long value = number(reader, fallback);
    return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
  }

  private static boolean bool(JsonReader reader) throws IOException {
    if (reader.peek() == JsonToken.BOOLEAN) {
      return reader.nextBoolean();
    }
    reader.skipValue();
    return false;
  }

  private static List<String> strings(JsonReader reader) throws IOException {
    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
      reader.skipValue();
      return List.of();
    }
    List<String> values = new ArrayList<>();
    reader.beginArray();
    while (reader.hasNext()) {
      String value = string(reader);
      if (value != null) {
        values.add(value);
      }
    }
    reader.endArray();
    return values;
  }

  private static BlockSize size(JsonReader reader) throws IOException {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
      reader.skipValue();
      return BlockSize.UNKNOWN;
    }
    int x = 0;
    int y = 0;
    int z = 0;
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case SIZE_X -> x = integer(reader, 0);
        case SIZE_Y -> y = integer(reader, 0);
        case SIZE_Z -> z = integer(reader, 0);
        default -> reader.skipValue();
      }
    }
    reader.endObject();
    return new BlockSize(x, y, z);
  }
}
