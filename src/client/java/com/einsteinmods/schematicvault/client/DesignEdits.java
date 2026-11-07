package com.einsteinmods.schematicvault.client;

import com.einsteinmods.schematicvault.core.edit.BlockReplacements;
import java.util.HashMap;
import java.util.Map;

/**
 * The block replacement rules chosen in this session, per design file (design id and file id):
 * leaving the full-screen view or looking at other designs keeps them; closing the vault forgets
 * them ({@link VaultSession#close}). Nothing is written to disk. Render thread.
 */
public final class DesignEdits {
  private final Map<Key, BlockReplacements> byFile = new HashMap<>();

  private record Key(String designId, String fileId) {}

  /** The replacements of a file; {@link BlockReplacements#NONE} when it has none. */
  public BlockReplacements get(String designId, String fileId) {
    return byFile.getOrDefault(new Key(designId, fileId), BlockReplacements.NONE);
  }

  public void put(String designId, String fileId, BlockReplacements replacements) {
    Key key = new Key(designId, fileId);
    if (replacements.isEmpty()) {
      byFile.remove(key);
    } else {
      byFile.put(key, replacements);
    }
  }

  void clear() {
    byFile.clear();
  }
}
