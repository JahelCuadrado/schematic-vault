package com.einsteinmods.schematicvault.core.catalog;

import java.util.List;

/**
 * One node of the category tree ({@code v1/taxonomy.json}).
 *
 * @param path slash-separated path from the root, e.g. {@code farms/mob/iron}
 * @param name English name; the shown name comes from the locale when it has one
 * @param count designs in this node and its children
 */
public record CategoryNode(String path, String name, int count, List<CategoryNode> children) {
  public CategoryNode {
    children = List.copyOf(children);
  }
}
