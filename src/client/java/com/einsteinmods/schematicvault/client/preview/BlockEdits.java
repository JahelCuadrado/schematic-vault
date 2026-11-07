package com.einsteinmods.schematicvault.client.preview;

import com.einsteinmods.schematicvault.core.edit.BlockStateRemap;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.PropertyValues;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.Remapped;
import com.einsteinmods.schematicvault.core.edit.BlockStateRemap.TargetBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The game's side of a block replacement: the new block's properties as plain data for {@link
 * BlockStateRemap}, the resulting game state, and whether the block-entity type stays. Any thread:
 * the registries are frozen.
 */
final class BlockEdits {
  private BlockEdits() {}

  /**
   * One old state as the new block.
   *
   * @param state the new state in the game
   * @param text the new state as a file writes it
   * @param keepBlockEntity whether the block-entity type stays (its saved data is kept)
   * @param lostProperties whether some property of the old state could not be carried over
   * @param dropsData whether the old block had a block entity whose data is dropped
   */
  record Edit(
      BlockState state,
      String text,
      boolean keepBlockEntity,
      boolean lostProperties,
      boolean dropsData) {}

  /**
   * What an old state becomes as {@code targetId}, or null when that block does not exist.
   *
   * @param old the old state in the game; null when the game does not know its block
   * @param oldProperties the old state's properties as the game names them
   * @param targetId the new block's id
   */
  static @Nullable Edit edit(
      @Nullable BlockState old, Map<String, String> oldProperties, String targetId) {
    Block target = block(targetId);
    if (target == null) {
      return null;
    }
    Remapped remapped = BlockStateRemap.remap(oldProperties, target(target));
    BlockState state = state(target, remapped.properties());
    BlockEntityType<?> oldType = old == null ? null : blockEntityType(old);
    boolean keep = Objects.equals(oldType, blockEntityType(state));
    return new Edit(
        state, remapped.state(), keep, remapped.lostProperties(), !keep && oldType != null);
  }

  /** A registered block by id, or null. */
  static @Nullable Block block(String id) {
    Identifier identifier = Identifier.tryParse(id);
    return identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
  }

  static String id(Block block) {
    return BuiltInRegistries.BLOCK.getKey(block).toString();
  }

  /** A block's properties, values and defaults as plain data, in the block's order. */
  static TargetBlock target(Block block) {
    BlockState defaults = block.defaultBlockState();
    Map<String, PropertyValues> properties = new LinkedHashMap<>();
    for (Property<?> property : block.getStateDefinition().getProperties()) {
      properties.put(property.getName(), values(property, defaults));
    }
    return new TargetBlock(id(block), properties);
  }

  private static <T extends Comparable<T>> PropertyValues values(
      Property<T> property, BlockState defaults) {
    List<String> names = new ArrayList<>();
    for (T value : property.getPossibleValues()) {
      names.add(property.getName(value));
    }
    return new PropertyValues(names, property.getName(defaults.getValue(property)));
  }

  /** A state's properties as the game names them, in the block's order. */
  static Map<String, String> properties(BlockState state) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (Property<?> property : state.getProperties()) {
      properties.put(property.getName(), valueName(state, property));
    }
    return properties;
  }

  private static <T extends Comparable<T>> String valueName(
      BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  /** The state of {@code block} with these properties; unknown names and values keep defaults. */
  static BlockState state(Block block, Map<String, String> properties) {
    BlockState state = block.defaultBlockState();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
      if (property != null) {
        state = withValue(state, property, entry.getValue());
      }
    }
    return state;
  }

  private static <T extends Comparable<T>> BlockState withValue(
      BlockState state, Property<T> property, String value) {
    return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
  }

  /** The block-entity type the game gives {@code state}, or null when it has none. */
  static @Nullable BlockEntityType<?> blockEntityType(BlockState state) {
    if (!state.hasBlockEntity()) {
      return null;
    }
    for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
      if (type.isValid(state)) {
        return type;
      }
    }
    return null;
  }

  /** A state as a file writes it ({@code minecraft:oak_stairs[facing=east,...]}). */
  static String text(BlockState state) {
    return new Remapped(id(state.getBlock()), properties(state), false).state();
  }
}
