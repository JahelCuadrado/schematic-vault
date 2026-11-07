package com.einsteinmods.schematicvault.client.screen;

import static java.util.Map.entry;

import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A vanilla item for each category of the API's tree ({@code v1/taxonomy.json}): every top-level
 * and second-level node has its own, and the mob farms too. A node without one (a new one, or a
 * deeper level such as {@code technical/storage/boxes/loaders}) takes its nearest parent's, and an
 * unknown top-level category takes {@link #FALLBACK}.
 */
final class CategoryIcons {
  private static final char PATH_SEPARATOR = '/';
  private static final Item ALL = Items.COMPASS;
  private static final Item FALLBACK = Items.PAPER;

  private static final Map<String, Item> ICONS =
      Map.ofEntries(
          entry("farms", Items.WHEAT),
          entry("farms/mob", Items.ZOMBIE_HEAD),
          entry("farms/mob/iron", Items.IRON_INGOT),
          entry("farms/mob/gold", Items.GOLD_INGOT),
          entry("farms/mob/experience", Items.EXPERIENCE_BOTTLE),
          entry("farms/mob/raid", Items.TOTEM_OF_UNDYING),
          entry("farms/mob/guardian", Items.PRISMARINE_SHARD),
          entry("farms/mob/slime", Items.SLIME_BALL),
          entry("farms/mob/magma-cube", Items.MAGMA_CREAM),
          entry("farms/mob/blaze", Items.BLAZE_ROD),
          entry("farms/mob/wither-skeleton", Items.WITHER_SKELETON_SKULL),
          entry("farms/mob/enderman", Items.ENDER_PEARL),
          entry("farms/mob/witch", Items.GLASS_BOTTLE),
          entry("farms/mob/creeper", Items.GUNPOWDER),
          entry("farms/mob/shulker", Items.SHULKER_SHELL),
          entry("farms/mob/drowned", Items.TRIDENT),
          entry("farms/mob/ghast", Items.GHAST_TEAR),
          entry("farms/mob/hoglin", Items.PORKCHOP),
          entry("farms/mob/bosses", Items.NETHER_STAR),
          entry("farms/mob/spawner", Items.SPAWNER),
          entry("farms/mob/general", Items.BONE),
          entry("farms/crops", Items.WHEAT_SEEDS),
          entry("farms/crops/sugar-cane", Items.SUGAR_CANE),
          entry("farms/crops/bamboo", Items.BAMBOO),
          entry("farms/crops/kelp", Items.KELP),
          entry("farms/crops/pumpkin-melon", Items.MELON_SLICE),
          entry("farms/crops/cactus", Items.CACTUS),
          entry("farms/crops/wheat-carrot-potato", Items.CARROT),
          entry("farms/crops/nether-wart", Items.NETHER_WART),
          entry("farms/crops/flowers", Items.POPPY),
          entry("farms/crops/mushroom-fungus", Items.RED_MUSHROOM),
          entry("farms/crops/aquatic", Items.SEA_PICKLE),
          entry("farms/crops/moss", Items.MOSS_BLOCK),
          entry("farms/crops/other", Items.BONE_MEAL),
          entry("farms/trees", Items.OAK_SAPLING),
          entry("farms/animal", Items.LEAD),
          entry("farms/animal/food", Items.BEEF),
          entry("farms/animal/wool", Items.SHEARS),
          entry("farms/animal/bees", Items.HONEYCOMB),
          entry("farms/animal/aquatic", Items.COD),
          entry("farms/animal/other", Items.EGG),
          entry("farms/resources", Items.IRON_PICKAXE),
          entry("farms/resources/stone", Items.COBBLESTONE),
          entry("farms/resources/basalt-blackstone", Items.BASALT),
          entry("farms/resources/obsidian", Items.OBSIDIAN),
          entry("farms/resources/concrete", Items.CONCRETE.cyan()),
          entry("farms/resources/ice-snow", Items.PACKED_ICE),
          entry("farms/resources/amethyst-dripstone", Items.AMETHYST_SHARD),
          entry("farms/resources/other", Items.FLINT),
          entry("farms/villagers", Items.EMERALD),
          entry("technical", Items.COMPARATOR),
          entry("technical/autocrafting", Items.CRAFTER),
          entry("technical/autocrafting/universal", Items.CRAFTING_TABLE),
          entry("technical/autocrafting/single-item", Items.DROPPER),
          entry("technical/autocrafting/compactor", Items.IRON_BLOCK),
          entry("technical/autocrafting/uncrafter", Items.IRON_NUGGET),
          entry("technical/autocrafting/factory", Items.PISTON),
          entry("technical/autocrafting/box-processing", Items.SHULKER_BOX),
          entry("technical/autocrafting/loaders", Items.HOPPER),
          entry("technical/autocrafting/collections", Items.BUNDLE),
          entry("technical/autocrafting/guides", Items.BOOK),
          entry("technical/autocrafting/other", Items.STICK),
          entry("technical/storage", Items.CHEST),
          entry("technical/storage/storage-systems", Items.BARREL),
          entry("technical/storage/chest-halls", Items.TRAPPED_CHEST),
          entry("technical/storage/bulk-storage", Items.ENDER_CHEST),
          entry("technical/storage/temporary-storage", Items.CHEST_MINECART),
          entry("technical/storage/sorting", Items.HOPPER),
          entry("technical/storage/boxes", Items.SHULKER_BOX),
          entry("technical/storage/item-call", Items.BELL),
          entry("technical/storage/interfaces", Items.ITEM_FRAME),
          entry("technical/storage/encoded", Items.NAME_TAG),
          entry("technical/storage/guides", Items.BOOK),
          entry("technical/storage/other", Items.BUNDLE),
          entry("technical/item-transport", Items.HOPPER_MINECART),
          entry("technical/smelting", Items.FURNACE),
          entry("technical/redstone", Items.REDSTONE),
          entry("technical/redstone/doors", Items.IRON_DOOR),
          entry("technical/redstone/circuits", Items.REPEATER),
          entry("technical/redstone/displays", Items.REDSTONE_LAMP),
          entry("technical/flying-machines", Items.SLIME_BLOCK),
          entry("technical/world-eaters", Items.TNT),
          entry("technical/duping", Items.STRING),
          entry("technical/player-transport", Items.MINECART),
          entry("technical/chunk-loaders", Items.ENDER_EYE),
          entry("technical/mob-switches", Items.LEVER),
          entry("technical/utilities", Items.OBSERVER),
          entry("builds", Items.BRICKS),
          entry("builds/houses", Items.OAK_DOOR),
          entry("builds/castles", Items.STONE_BRICKS),
          entry("builds/towers", Items.COBBLESTONE_WALL),
          entry("builds/religious", Items.BELL),
          entry("builds/public", Items.LANTERN),
          entry("builds/commercial", Items.EMERALD_BLOCK),
          entry("builds/industrial", Items.BLAST_FURNACE),
          entry("builds/infrastructure", Items.RAIL),
          entry("builds/vehicles", Items.OAK_BOAT),
          entry("builds/settlements", Items.CAMPFIRE),
          entry("builds/terrain", Items.GRASS_BLOCK),
          entry("builds/interiors", Items.BOOKSHELF),
          entry("builds/art", Items.PAINTING),
          entry("builds/art/statues", Items.ARMOR_STAND),
          entry("builds/art/pixel-art", Items.MAP),
          entry("games", Items.TARGET),
          entry("other", Items.PAPER));

  private CategoryIcons() {}

  /** A new stack of the icon of {@code path}; the "All" row's for an empty path. */
  static ItemStack icon(String path) {
    return new ItemStack(item(path));
  }

  static Item item(String path) {
    if (path.isEmpty()) {
      return ALL;
    }
    String node = path;
    while (true) {
      Item icon = ICONS.get(node);
      if (icon != null) {
        return icon;
      }
      int parentEnd = node.lastIndexOf(PATH_SEPARATOR);
      if (parentEnd < 0) {
        return FALLBACK;
      }
      node = node.substring(0, parentEnd);
    }
  }
}
