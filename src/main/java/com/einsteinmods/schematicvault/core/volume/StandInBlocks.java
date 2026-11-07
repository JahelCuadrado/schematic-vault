package com.einsteinmods.schematicvault.core.volume;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A vanilla block to draw in place of one the game does not know (a block of a mod the player does
 * not have, an old name nothing renames), for the preview only: files keep the original ids. Pure:
 * the client says which ids its registry knows.
 *
 * <p>Order: a block the game knows stays itself; then the longest vanilla block the id's path is or
 * ends with ({@code connectedglass:borderless_glass} → {@code glass}, {@code
 * adorn:candlelit_lantern} → {@code lantern}), split on {@code _}; then the first {@link #TABLE}
 * entry whose word the path holds; then {@link #PLACEHOLDER}. Never an invisible block.
 */
public final class StandInBlocks {
  /** Drawn for a block nothing else matches: clearly visible, never air. */
  public static final String PLACEHOLDER = "minecraft:magenta_wool";

  private static final String VANILLA_NAMESPACE = "minecraft";
  private static final char NAMESPACE_SEPARATOR = ':';
  private static final char WORD_SEPARATOR = '_';
  private static final String NAME_SEPARATORS = "[_\\-]+";
  private static final String NAME_JOINER = " ";

  /** Blocks that draw nothing (or that the game only places itself): never a stand-in. */
  private static final Set<String> INVISIBLE =
      Set.of(
          "minecraft:air",
          "minecraft:cave_air",
          "minecraft:void_air",
          "minecraft:light",
          "minecraft:barrier",
          "minecraft:structure_void",
          "minecraft:moving_piston");

  /**
   * Common shapes and materials, first match wins: a word the path holds, and the block drawn for
   * it. Shapes come before materials ({@code oak_planks_stairs} is stairs), panes before glass,
   * trapdoors before doors, lights before stone ({@code glowstone_lamp}).
   */
  private static final List<Entry> TABLE =
      List.of(
          new Entry("pane", "minecraft:glass_pane"),
          new Entry("glass", "minecraft:glass"),
          new Entry("stairs", "minecraft:stone_stairs"),
          new Entry("slab", "minecraft:stone_slab"),
          new Entry("fence", "minecraft:oak_fence"),
          new Entry("trapdoor", "minecraft:oak_trapdoor"),
          new Entry("door", "minecraft:oak_door"),
          new Entry("chest", "minecraft:chest"),
          new Entry("crate", "minecraft:barrel"),
          new Entry("lamp", "minecraft:glowstone"),
          new Entry("light", "minecraft:glowstone"),
          new Entry("leaves", "minecraft:oak_leaves"),
          new Entry("planks", "minecraft:oak_planks"),
          new Entry("log", "minecraft:oak_log"),
          new Entry("wood", "minecraft:oak_log"),
          new Entry("wool", "minecraft:white_wool"),
          new Entry("brick", "minecraft:bricks"),
          new Entry("stone", "minecraft:stone"));

  private StandInBlocks() {}

  /** How a stand-in was found. */
  public enum Match {
    /** The game knows the block: it is drawn as itself. */
    KNOWN,
    /** The id's path is or ends with a vanilla block's path. */
    SUFFIX,
    /** A {@link #TABLE} word. */
    TABLE,
    /** Nothing matched: {@link #PLACEHOLDER}. */
    PLACEHOLDER
  }

  /**
   * The block drawn for an id.
   *
   * @param id the block's id, namespaced
   */
  public record StandIn(String id, Match match) {}

  private record Entry(String word, String block) {}

  /**
   * The block to draw for {@code id}.
   *
   * @param id a namespaced block id ({@code connectedglass:clear_glass})
   * @param isBlock whether the game's registry knows a namespaced id
   */
  public static StandIn of(String id, Predicate<String> isBlock) {
    if (isBlock.test(id) && !INVISIBLE.contains(id)) {
      return new StandIn(id, Match.KNOWN);
    }
    String path = path(id).toLowerCase(Locale.ROOT);
    for (int start = 0; start >= 0; start = nextWord(path, start)) {
      String candidate = VANILLA_NAMESPACE + NAMESPACE_SEPARATOR + path.substring(start);
      if (usable(candidate, isBlock)) {
        return new StandIn(candidate, Match.SUFFIX);
      }
    }
    for (Entry entry : TABLE) {
      if (path.contains(entry.word()) && usable(entry.block(), isBlock)) {
        return new StandIn(entry.block(), Match.TABLE);
      }
    }
    return new StandIn(PLACEHOLDER, Match.PLACEHOLDER);
  }

  /**
   * Whether {@code id} is an old block rather than a mod's: an old {@code minecraft} name nothing
   * renames, or a numeric block of an old file the legacy table lacks ({@link
   * LegacyBlocks#NAMESPACE}).
   */
  public static boolean isOldBlock(String id) {
    String namespace = namespace(id);
    return namespace.equals(VANILLA_NAMESPACE) || namespace.equals(LegacyBlocks.NAMESPACE);
  }

  /** The id's namespace ({@code minecraft} when it has none). */
  public static String namespace(String id) {
    int separator = id.indexOf(NAMESPACE_SEPARATOR);
    return separator < 0 ? VANILLA_NAMESPACE : id.substring(0, separator);
  }

  /** The id's path, after the namespace. */
  public static String path(String id) {
    return id.substring(id.indexOf(NAMESPACE_SEPARATOR) + 1);
  }

  /**
   * A namespace or path as words: split on {@code _} and {@code -}, each word capitalised ({@code
   * redstone_sharestone} → {@code Redstone Sharestone}).
   */
  public static String readable(String name) {
    List<String> words = new ArrayList<>();
    for (String word : name.split(NAME_SEPARATORS)) {
      if (!word.isEmpty()) {
        words.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
      }
    }
    return String.join(NAME_JOINER, words);
  }

  /** The readable names of the mods these ids come from, sorted, once each; old blocks left out. */
  public static List<String> modNames(Collection<String> ids) {
    Set<String> names = new TreeSet<>();
    for (String id : ids) {
      if (!isOldBlock(id)) {
        names.add(readable(namespace(id)));
      }
    }
    return List.copyOf(names);
  }

  /** Whether any of these ids is an old block ({@link #isOldBlock}). */
  public static boolean hasOldBlocks(Collection<String> ids) {
    for (String id : ids) {
      if (isOldBlock(id)) {
        return true;
      }
    }
    return false;
  }

  /** The first {@code max} names, then {@code more} when some are left out. */
  public static List<String> firstNames(List<String> names, int max, String more) {
    if (names.size() <= max) {
      return names;
    }
    List<String> shown = new ArrayList<>(names.subList(0, max));
    shown.add(more);
    return List.copyOf(shown);
  }

  private static boolean usable(String id, Predicate<String> isBlock) {
    return !INVISIBLE.contains(id) && isBlock.test(id);
  }

  /** Where the word after the one at {@code start} begins; -1 after the last one. */
  private static int nextWord(String path, int start) {
    int separator = path.indexOf(WORD_SEPARATOR, start);
    return separator < 0 ? -1 : separator + 1;
  }
}
