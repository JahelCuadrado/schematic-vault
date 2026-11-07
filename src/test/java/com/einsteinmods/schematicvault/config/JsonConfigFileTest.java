package com.einsteinmods.schematicvault.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.einsteinmods.schematicvault.core.config.VaultSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonConfigFileTest {
  @TempDir Path folder;

  @Test
  void saveReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
    Path path = folder.resolve(VaultConfigFile.FILE_NAME);
    JsonConfigFile<VaultConfigFile> file =
        new JsonConfigFile<>(path, VaultConfigFile.class, VaultConfigFile.DEFAULTS);
    file.save(VaultConfigFile.DEFAULTS);
    VaultSettings edited = new VaultSettings("https://example.org/api/", "Farms", 150_000);
    file.save(VaultConfigFile.of(edited));

    assertEquals(Optional.of(edited), file.read().map(VaultConfigFile::toSettings));
    try (Stream<Path> files = Files.list(folder)) {
      assertEquals(List.of(path), files.toList());
    }
  }
}
