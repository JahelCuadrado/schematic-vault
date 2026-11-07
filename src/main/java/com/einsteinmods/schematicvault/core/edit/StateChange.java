package com.einsteinmods.schematicvault.core.edit;

/**
 * What one palette entry of a file becomes.
 *
 * @param state the new block state as text ({@code minecraft:air} removes the block)
 * @param keepBlockEntity whether the old block's block-entity data (chest contents, sign text)
 *     stays: only when the new block has the same block-entity type
 */
public record StateChange(String state, boolean keepBlockEntity) {}
