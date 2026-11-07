package com.einsteinmods.schematicvault.core.catalog;

import com.einsteinmods.schematicvault.core.files.SchematicFormat;
import com.einsteinmods.schematicvault.core.files.Sha256;
import com.einsteinmods.schematicvault.core.net.RemotePaths;
import com.einsteinmods.schematicvault.core.net.WebLinks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the API's JSON (manifest, catalog pages, taxonomy, locale, a design's record). Unknown
 * fields are ignored and optional ones may be missing; a design or file without what the mod needs
 * (id, a safe path, a SHA-256, a known format) is left out instead of failing the whole page. Every
 * API path is checked with {@link RemotePaths} here, so later code only sees safe ones.
 */
public final class ApiJson {
  public static final String PAGE_PLACEHOLDER = "{page}";
  public static final String ID_PLACEHOLDER = "{id}";
  public static final String LANGUAGE_PLACEHOLDER = "{language}";
  public static final String PART_PLACEHOLDER = "{part}";

  /** More pages than this is not a catalog the mod can hold (the API has 64 of 100). */
  public static final int MAX_PAGES = 1000;

  /** More index parts than this is not an index the mod reads (the API has 4 of 20,000 rows). */
  public static final int MAX_INDEX_PARTS = 100;

  private static final String DEFAULT_PAGE_TEMPLATE = "v1/schematics/pages/{page}.json";
  public static final String DEFAULT_SCHEMATIC_TEMPLATE = "v1/schematics/{id}.json";
  private static final String DEFAULT_TAXONOMY_PATH = "v1/taxonomy.json";
  private static final String DEFAULT_LOCALE_TEMPLATE = "v1/locales/{language}.json";

  private static final String REVISION = "revision";
  private static final String GENERATED_AT = "generatedAt";
  private static final String PAGINATION = "pagination";
  private static final String PAGE_COUNT = "pageCount";
  private static final String ENDPOINTS = "endpoints";
  private static final String ENDPOINT_PAGE = "page";
  private static final String ENDPOINT_SCHEMATIC = "schematic";
  private static final String ENDPOINT_TAXONOMY = "taxonomy";
  private static final String ENDPOINT_LOCALE = "locale";
  private static final String ENDPOINT_INDEX = "index";
  private static final String INDEX = "index";
  private static final String PART_COUNT = "partCount";
  private static final String LANGUAGES = "languages";
  private static final String SHARDS = "shards";
  private static final String SHARD_COUNT = "count";
  private static final String SHARD_URL_TEMPLATE = "urlTemplate";
  private static final String SHARD_PLACEHOLDER = "{shard}";
  private static final String SHARD_NUMBER_FORMAT = "%02d";

  /** {@code shards.recordRule} pads shard numbers to two digits. */
  public static final int MAX_SHARDS = 100;

  private static final String ITEMS = "items";
  private static final String ID = "id";
  private static final String TITLE = "title";
  private static final String AUTHORS = "authors";
  private static final String TAGS = "tags";
  private static final String CATEGORY = "category";
  private static final String LICENSE = "license";
  private static final String SOURCE_ID = "sourceId";
  private static final String MINECRAFT_VERSION = "minecraftVersion";
  private static final String SIZE = "size";
  private static final String SIZE_X = "x";
  private static final String SIZE_Y = "y";
  private static final String SIZE_Z = "z";
  private static final String BLOCK_COUNT = "blockCount";
  private static final String DIFFICULTY = "difficulty";
  private static final String VERIFIED = "verified";
  private static final String WIDTH = "width";
  private static final String PRIMARY_FILE_URL = "primaryFileUrl";
  private static final String THUMBNAIL_PNG_URL = "thumbnailPngUrl";
  private static final String UPDATED_AT = "updatedAt";
  private static final String STATUS = "status";
  private static final String DATA_VERSION_MAX = "dataVersionMax";
  private static final String POPULARITY = "popularity";
  private static final String SOURCE_STATUS = "sourceStatus";
  private static final String ENDORSEMENT_COUNT = "endorsementCount";
  private static final String HAS_RATES = "hasRates";
  private static final String NODES = "nodes";
  private static final String PATH = "path";
  private static final String NAME = "name";
  private static final String COUNT = "count";
  private static final String CHILDREN = "children";
  private static final String CATEGORIES = "categories";
  private static final String ROLE = "role";
  private static final String SOURCE = "source";
  private static final String URL = "url";
  private static final String PRIMARY_FILE_ID = "primaryFileId";
  private static final String FILES = "files";
  private static final String FILE_NAME = "fileName";
  private static final String FORMAT = "format";
  private static final String SIZE_BYTES = "sizeBytes";
  private static final String SHA256 = "sha256";
  private static final String TITLES = "titles";
  private static final String DESCRIPTION = "description";
  private static final String SUMMARY = "summary";
  private static final String TRANSLATIONS = "translations";

  /** Endpoint placeholders such as {@code {page}}: not path characters, checked as a sample. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z]+}");

  private static final String PLACEHOLDER_SAMPLE = "x";

  private ApiJson() {}

  public static Manifest manifest(String json) throws CatalogFormatException {
    JsonObject root = root(json);
    String revision = JsonFields.string(root, REVISION);
    if (revision == null || revision.isBlank()) {
      throw new CatalogFormatException("manifest without a revision");
    }
    JsonObject pagination = JsonFields.object(root, PAGINATION);
    int pageCount = pagination == null ? 0 : JsonFields.integer(pagination, PAGE_COUNT, 0);
    if (pageCount < 1 || pageCount > MAX_PAGES) {
      throw new CatalogFormatException("manifest with " + pageCount + " pages");
    }
    JsonObject endpoints = JsonFields.object(root, ENDPOINTS);
    return new Manifest(
        revision,
        JsonFields.string(root, GENERATED_AT, ""),
        pageCount,
        endpoint(endpoints, ENDPOINT_PAGE, DEFAULT_PAGE_TEMPLATE),
        endpoint(endpoints, ENDPOINT_SCHEMATIC, DEFAULT_SCHEMATIC_TEMPLATE),
        endpoint(endpoints, ENDPOINT_TAXONOMY, DEFAULT_TAXONOMY_PATH),
        endpoint(endpoints, ENDPOINT_LOCALE, DEFAULT_LOCALE_TEMPLATE),
        JsonFields.strings(root, LANGUAGES),
        shardRoots(JsonFields.object(root, SHARDS)),
        index(endpoints, JsonFields.object(root, INDEX)));
  }

  /**
   * The compact index when both {@code endpoints.index} (with its {@code {part}}) and a usable
   * {@code index.partCount} are given; null otherwise, so the pages are read.
   */
  private static @Nullable IndexEndpoint index(
      @Nullable JsonObject endpoints, @Nullable JsonObject index) throws CatalogFormatException {
    String template = endpoints == null ? null : JsonFields.string(endpoints, ENDPOINT_INDEX);
    int partCount = index == null ? 0 : JsonFields.integer(index, PART_COUNT, 0);
    if (template == null
        || !template.contains(PART_PLACEHOLDER)
        || partCount < 1
        || partCount > MAX_INDEX_PARTS) {
      return null;
    }
    return new IndexEndpoint(endpoint(endpoints, ENDPOINT_INDEX, template), partCount);
  }

  /** Each shard's {@code https://host/}, in order; empty without shards. */
  private static List<String> shardRoots(@Nullable JsonObject shards)
      throws CatalogFormatException {
    if (shards == null) {
      return List.of();
    }
    int count = JsonFields.integer(shards, SHARD_COUNT, 0);
    String template = JsonFields.string(shards, SHARD_URL_TEMPLATE);
    if (count < 1
        || count > MAX_SHARDS
        || template == null
        || !template.contains(SHARD_PLACEHOLDER)) {
      throw new CatalogFormatException("manifest with unusable shards");
    }
    List<String> roots = new ArrayList<>(count);
    for (int shard = 0; shard < count; shard++) {
      String root =
          RemotePaths.hostRoot(
              template.replace(SHARD_PLACEHOLDER, String.format(SHARD_NUMBER_FORMAT, shard)));
      if (root == null) {
        throw new CatalogFormatException("unsafe shard template");
      }
      roots.add(root);
    }
    return roots;
  }

  /** The designs of one catalog page, in page order. */
  public static List<SchematicSummary> page(String json) throws CatalogFormatException {
    JsonObject root = root(json);
    List<SchematicSummary> schematics = new ArrayList<>();
    for (JsonElement element : JsonFields.array(root, ITEMS)) {
      if (element instanceof JsonObject item) {
        summary(item).ifPresent(schematics::add);
      }
    }
    return schematics;
  }

  public static List<CategoryNode> taxonomy(String json) throws CatalogFormatException {
    return nodes(root(json), NODES);
  }

  /**
   * The texts of a locale file: category names ({@code categories.<path>.name}), titles ({@code
   * titles.<id>}) and tag names ({@code tags.<id>}); blank ones are left out.
   *
   * @param language the API language the file was fetched for
   */
  public static LocaleTexts locale(String language, String json) throws CatalogFormatException {
    JsonObject root = root(json);
    Map<String, String> categoryNames = new HashMap<>();
    JsonObject categories = JsonFields.object(root, CATEGORIES);
    if (categories != null) {
      for (Map.Entry<String, JsonElement> entry : categories.entrySet()) {
        if (entry.getValue() instanceof JsonObject category) {
          putNonBlank(categoryNames, entry.getKey(), JsonFields.string(category, NAME));
        }
      }
    }
    return new LocaleTexts(
        language, categoryNames, stringValues(root, TITLES), stringValues(root, TAGS));
  }

  public static SchematicDetail detail(String json) throws CatalogFormatException {
    JsonObject root = root(json);
    String id = JsonFields.string(root, ID);
    if (id == null) {
      throw new CatalogFormatException("design record without an id");
    }
    List<SchematicAuthor> authors = new ArrayList<>();
    for (JsonElement element : JsonFields.array(root, AUTHORS)) {
      if (element instanceof JsonObject author) {
        String name = JsonFields.string(author, NAME);
        if (name != null && !name.isBlank()) {
          authors.add(new SchematicAuthor(name, JsonFields.string(author, ROLE)));
        }
      }
    }
    JsonObject source = JsonFields.object(root, SOURCE);
    String sourceName = source == null ? null : JsonFields.string(source, NAME);
    String sourceUrl = source == null ? null : webLink(JsonFields.string(source, URL));
    List<SchematicFile> files = new ArrayList<>();
    for (JsonElement element : JsonFields.array(root, FILES)) {
      if (element instanceof JsonObject file) {
        file(file).ifPresent(files::add);
      }
    }
    Map<String, DetailText> translations = new HashMap<>();
    JsonObject translated = JsonFields.object(root, TRANSLATIONS);
    if (translated != null) {
      for (Map.Entry<String, JsonElement> entry : translated.entrySet()) {
        if (entry.getValue() instanceof JsonObject text) {
          translations.put(entry.getKey(), detailText(text));
        }
      }
    }
    return new SchematicDetail(
        id,
        detailText(root),
        JsonFields.string(root, SUMMARY, ""),
        translations,
        authors,
        sourceName,
        sourceUrl,
        JsonFields.string(root, PRIMARY_FILE_ID),
        files);
  }

  private static DetailText detailText(JsonObject object) {
    return new DetailText(
        JsonFields.string(object, TITLE, ""), JsonFields.string(object, DESCRIPTION, ""));
  }

  /** The non-blank string values of an object field, by key. */
  private static Map<String, String> stringValues(JsonObject parent, String key) {
    Map<String, String> values = new HashMap<>();
    JsonObject object = JsonFields.object(parent, key);
    if (object != null) {
      for (String name : object.keySet()) {
        putNonBlank(values, name, JsonFields.string(object, name));
      }
    }
    return values;
  }

  private static void putNonBlank(Map<String, String> values, String key, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      values.put(key, value);
    }
  }

  private static Optional<SchematicSummary> summary(JsonObject item) {
    String id = JsonFields.string(item, ID);
    String title = JsonFields.string(item, TITLE);
    if (id == null || id.isBlank() || title == null) {
      return Optional.empty();
    }
    JsonObject size = JsonFields.object(item, SIZE);
    BlockSize blockSize =
        size == null
            ? BlockSize.UNKNOWN
            : new BlockSize(
                JsonFields.integer(size, SIZE_X, 0),
                JsonFields.integer(size, SIZE_Y, 0),
                JsonFields.integer(size, SIZE_Z, 0));
    return Optional.of(
        new SchematicSummary(
            id,
            title,
            JsonFields.strings(item, AUTHORS),
            JsonFields.strings(item, TAGS),
            JsonFields.string(item, CATEGORY, ""),
            JsonFields.string(item, LICENSE),
            JsonFields.string(item, SOURCE_ID, ""),
            JsonFields.string(item, MINECRAFT_VERSION),
            blockSize,
            Math.max(0, JsonFields.number(item, BLOCK_COUNT, 0)),
            Difficulty.fromId(JsonFields.string(item, DIFFICULTY, "")).orElse(null),
            JsonFields.bool(item, VERIFIED),
            Math.max(0, JsonFields.integer(item, WIDTH, 0)),
            apiPath(JsonFields.string(item, PRIMARY_FILE_URL)),
            apiPath(JsonFields.string(item, THUMBNAIL_PNG_URL)),
            JsonFields.string(item, UPDATED_AT, ""),
            ReviewStatus.fromId(JsonFields.string(item, STATUS, "")).orElse(null),
            JsonFields.integer(item, DATA_VERSION_MAX, 0),
            !JsonFields.string(item, SUMMARY, "").isBlank(),
            JsonFields.integer(item, POPULARITY, SchematicSummary.NO_POPULARITY),
            SourceStatus.fromId(JsonFields.string(item, SOURCE_STATUS, "")).orElse(null),
            JsonFields.integer(item, ENDORSEMENT_COUNT, 0),
            JsonFields.bool(item, HAS_RATES)));
  }

  private static Optional<SchematicFile> file(JsonObject file) {
    String id = JsonFields.string(file, ID);
    String url = apiPath(JsonFields.string(file, URL));
    String sha256 = JsonFields.string(file, SHA256);
    Optional<SchematicFormat> format = SchematicFormat.fromId(JsonFields.string(file, FORMAT, ""));
    if (id == null || url == null || sha256 == null || !Sha256.isWellFormed(sha256)) {
      return Optional.empty();
    }
    return format.map(
        known ->
            new SchematicFile(
                id,
                JsonFields.string(file, FILE_NAME, id),
                known,
                Math.max(0, JsonFields.number(file, SIZE_BYTES, 0)),
                Sha256.normalize(sha256),
                url,
                JsonFields.string(file, MINECRAFT_VERSION)));
  }

  private static List<CategoryNode> nodes(JsonObject parent, String key) {
    List<CategoryNode> nodes = new ArrayList<>();
    for (JsonElement element : JsonFields.array(parent, key)) {
      if (element instanceof JsonObject node) {
        String path = JsonFields.string(node, PATH);
        if (path != null && !path.isBlank()) {
          nodes.add(
              new CategoryNode(
                  path,
                  JsonFields.string(node, NAME, path),
                  JsonFields.integer(node, COUNT, 0),
                  nodes(node, CHILDREN)));
        }
      }
    }
    return nodes;
  }

  private static String endpoint(@Nullable JsonObject endpoints, String key, String fallback)
      throws CatalogFormatException {
    String template = endpoints == null ? null : JsonFields.string(endpoints, key);
    if (template == null) {
      return fallback;
    }
    String sample = PLACEHOLDER.matcher(template).replaceAll(PLACEHOLDER_SAMPLE);
    if (!RemotePaths.isSafe(sample)) {
      throw new CatalogFormatException("unsafe endpoint " + key);
    }
    return template;
  }

  private static @Nullable String apiPath(@Nullable String path) {
    return path == null ? null : RemotePaths.checkedAddress(path).orElse(null);
  }

  private static @Nullable String webLink(@Nullable String url) {
    return url == null ? null : WebLinks.parse(url).map(URI::toString).orElse(null);
  }

  private static JsonObject root(String json) throws CatalogFormatException {
    try {
      if (JsonParser.parseString(json) instanceof JsonObject object) {
        return object;
      }
    } catch (JsonParseException exception) {
      throw new CatalogFormatException("invalid JSON", exception);
    }
    throw new CatalogFormatException("JSON is not an object");
  }
}
