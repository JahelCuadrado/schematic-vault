package com.einsteinmods.schematicvault.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves one JSON config file. Invalid or missing files fall back to defaults (and are
 * rewritten) so a broken file never prevents the game from starting.
 */
public final class JsonConfigFile<T extends Record> {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsonConfigFile.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String TEMP_PREFIX = ".schematicvault-";
  private static final String TEMP_SUFFIX = ".tmp";

  private final Path path;
  private final Class<T> type;
  private final T defaults;

  public JsonConfigFile(Path path, Class<T> type, T defaults) {
    this.path = path;
    this.type = type;
    this.defaults = defaults;
  }

  /** Reads the file; writes and returns defaults when it is missing or unreadable. */
  public T load() {
    Optional<T> value = read();
    if (value.isPresent()) {
      return value.get();
    }
    save(defaults);
    return defaults;
  }

  /**
   * Reads the file without writing anything: empty when it is missing or unreadable (logged), so a
   * hand-edited file with a mistake stays as it is until the next save.
   */
  public Optional<T> read() {
    if (Files.notExists(path)) {
      return Optional.empty();
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      T value = GSON.fromJson(reader, type);
      if (value != null) {
        return Optional.of(value);
      }
      LOGGER.warn("Config file {} is empty; using defaults", path);
    } catch (IOException | JsonParseException exception) {
      LOGGER.warn("Config file {} is invalid; using defaults", path, exception);
    }
    return Optional.empty();
  }

  /**
   * Writes through a temporary file in the same folder, then one atomic move: a crash or a full
   * disk leaves the old file, never a half-written one. A failure is logged.
   */
  public void save(T value) {
    try {
      Path folder = path.toAbsolutePath().getParent();
      Files.createDirectories(folder);
      Path temp = Files.createTempFile(folder, TEMP_PREFIX, TEMP_SUFFIX);
      try {
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
          GSON.toJson(value, type, writer);
        }
        moveInto(temp);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException exception) {
      LOGGER.error("Could not write config file {}", path, exception);
    }
  }

  private void moveInto(Path temp) throws IOException {
    try {
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
