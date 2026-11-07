package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.core.edit.BlockShapes;
import com.einsteinmods.schematicvault.core.volume.LayerRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the block that replaces one block type of a design: a search box (translated name or id)
 * over every block a player can place (those with an item; technical blocks without one are left
 * out) plus "Air (remove)", with suggestions of the same shape on top (same id suffix, such as
 * {@code _stairs}; else the same set of properties). The candidates are built once per opening; a
 * search filters them (one pass), nothing runs per frame but the rows on screen.
 */
final class BlockPickerScreen extends Screen {
  private static final int MARGIN = 6;
  private static final int GAP = 4;
  private static final int TITLE_TOP = 8;
  private static final int ROW_HEIGHT = 20;
  private static final int ICON_SIZE = 16;
  private static final int MAX_WIDTH = 320;
  private static final int BUTTON_WIDTH = 150;
  private static final int MAX_QUERY_LENGTH = 64;
  private static final int TITLE_COLOR = 0xFFFFFFFF;
  private static final int NAME_COLOR = 0xFFFFFFFF;
  private static final int ID_COLOR = 0xFF808080;
  private static final int HEADER_COLOR = 0xFFFFD040;

  private static final Component SEARCH = Component.translatable("schematicvault.picker.search");
  private static final Component SUGGESTED =
      Component.translatable("schematicvault.picker.suggested");
  private static final Component ALL = Component.translatable("schematicvault.picker.all");

  private final Screen parent;
  private final Consumer<String> onPick;
  private final List<Candidate> suggestions;
  private final List<Candidate> all;
  private String query = "";
  private @Nullable EditBox searchBox;

  /**
   * @param oldBlockId the block being replaced
   * @param oldBlock its game block; null when the game does not know it (no suggestions)
   * @param layers the layers the choice applies to, shown in the title
   * @param onPick receives the chosen block's id
   */
  BlockPickerScreen(
      Screen parent,
      String oldBlockId,
      @Nullable Block oldBlock,
      LayerRange layers,
      Consumer<String> onPick) {
    super(
        Component.translatable(
            "schematicvault.picker.title",
            BlockListPanel.scoped(BlockListPanel.name(oldBlock, oldBlockId), layers)));
    this.parent = parent;
    this.onPick = onPick;
    this.all = candidates();
    this.suggestions = suggestions(all, oldBlockId, oldBlock);
  }

  /** A block offered: its id, translated name, lowercase search text and icon. */
  private record Candidate(Block block, String id, String name, String search, ItemStack icon) {}

  /** Air first, then every block with an item, by name. */
  private static List<Candidate> candidates() {
    List<Candidate> blocks = new ArrayList<>();
    for (Block block : BuiltInRegistries.BLOCK) {
      if (block.asItem() != Items.AIR) {
        blocks.add(candidate(block, block.getName().getString(), new ItemStack(block.asItem())));
      }
    }
    blocks.sort(Comparator.comparing(Candidate::name));
    List<Candidate> candidates = new ArrayList<>(blocks.size() + 1);
    candidates.add(
        candidate(
            Blocks.AIR,
            BlockListPanel.name(Blocks.AIR, BuiltInRegistries.BLOCK.getKey(Blocks.AIR).toString()),
            new ItemStack(Items.BARRIER)));
    candidates.addAll(blocks);
    return candidates;
  }

  private static Candidate candidate(Block block, String name, ItemStack icon) {
    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
    return new Candidate(block, id, name, (name + " " + id).toLowerCase(Locale.ROOT), icon);
  }

  /** Blocks of the old one's shape (by id suffix, else by its set of properties). */
  private static List<Candidate> suggestions(
      List<Candidate> all, String oldBlockId, @Nullable Block oldBlock) {
    boolean bySuffix = BlockShapes.suffix(oldBlockId).isPresent();
    Set<String> oldProperties = oldBlock == null ? Set.of() : propertyNames(oldBlock);
    if (!bySuffix && oldProperties.isEmpty()) {
      return List.of();
    }
    List<Candidate> suggested = new ArrayList<>();
    for (Candidate candidate : all) {
      if (candidate.id().equals(oldBlockId)) {
        continue;
      }
      boolean same =
          bySuffix
              ? BlockShapes.sameShape(oldBlockId, candidate.id())
              : propertyNames(candidate.block()).equals(oldProperties);
      if (same) {
        suggested.add(candidate);
      }
    }
    return suggested;
  }

  private static Set<String> propertyNames(Block block) {
    Set<String> names = new HashSet<>();
    for (Property<?> property : block.getStateDefinition().getProperties()) {
      names.add(property.getName());
    }
    return names;
  }

  @Override
  protected void init() {
    int contentWidth = Math.min(MAX_WIDTH, width - MARGIN * 2);
    int left = (width - contentWidth) / 2;
    int searchTop = TITLE_TOP + font.lineHeight + GAP;
    EditBox search =
        new EditBox(font, left, searchTop, contentWidth, Button.DEFAULT_HEIGHT, searchBox, SEARCH);
    search.setMaxLength(MAX_QUERY_LENGTH);
    search.setHint(SEARCH.copy().withStyle(EditBox.SEARCH_HINT_STYLE));
    searchBox = addRenderableWidget(search);
    int footerTop = height - MARGIN - Button.DEFAULT_HEIGHT;
    int listTop = searchTop + Button.DEFAULT_HEIGHT + GAP;
    int listHeight = Math.max(ROW_HEIGHT, footerTop - GAP - listTop);
    Candidates list = new Candidates(contentWidth, listHeight, listTop);
    list.updateSizeAndPosition(contentWidth, listHeight, left, listTop);
    list.show(query);
    addRenderableWidget(list);
    search.setResponder(
        value -> {
          if (!value.equals(query)) {
            query = value;
            list.show(value);
          }
        });
    addRenderableWidget(
        Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
            .bounds((width - BUTTON_WIDTH) / 2, footerTop, BUTTON_WIDTH, Button.DEFAULT_HEIGHT)
            .build());
    setInitialFocus(search);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    graphics.centeredText(font, title, width / 2, TITLE_TOP, TITLE_COLOR);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private void pick(Candidate candidate) {
    onPick.accept(candidate.id());
    onClose();
  }

  /** The rows, made once per list: a search only picks which are shown. */
  private final class Candidates extends ObjectSelectionList<Candidates.Entry> {
    private final Header suggestedHeader = new Header(SUGGESTED);
    private final Header allHeader = new Header(ALL);
    private final List<BlockEntry> suggestedRows = rows(suggestions);
    private final List<BlockEntry> allRows = rows(all);

    Candidates(int listWidth, int listHeight, int top) {
      super(BlockPickerScreen.this.minecraft, listWidth, listHeight, top, ROW_HEIGHT);
    }

    private List<BlockEntry> rows(List<Candidate> candidates) {
      List<BlockEntry> rows = new ArrayList<>(candidates.size());
      for (Candidate candidate : candidates) {
        rows.add(new BlockEntry(candidate));
      }
      return rows;
    }

    /** The candidates matching {@code value}: suggestions first, under their headers. */
    void show(String value) {
      String needle = value.strip().toLowerCase(Locale.ROOT);
      List<Entry> entries = new ArrayList<>();
      addMatches(entries, suggestedHeader, suggestedRows, needle);
      addMatches(entries, allHeader, allRows, needle);
      replaceEntries(entries);
      setScrollAmount(0);
    }

    private static void addMatches(
        List<Entry> entries, Header header, List<BlockEntry> rows, String needle) {
      boolean headed = false;
      for (BlockEntry row : rows) {
        if (!needle.isEmpty() && !row.candidate.search().contains(needle)) {
          continue;
        }
        if (!headed) {
          entries.add(header);
          headed = true;
        }
        entries.add(row);
      }
    }

    @Override
    public int getRowWidth() {
      return width - scrollbarWidth() * 2;
    }

    private abstract static class Entry extends ObjectSelectionList.Entry<Entry> {}

    private final class Header extends Entry {
      private final Component text;

      Header(Component text) {
        this.text = text;
      }

      @Override
      public void extractContent(
          GuiGraphicsExtractor graphics,
          int mouseX,
          int mouseY,
          boolean hovered,
          float partialTick) {
        int y = getContentY() + (getContentHeight() - font.lineHeight) / 2 + 1;
        graphics.text(font, text, getContentX(), y, HEADER_COLOR);
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
      }

      @Override
      public Component getNarration() {
        return text;
      }
    }

    private final class BlockEntry extends Entry {
      private final Candidate candidate;
      private final int nameWidth;
      private final int idWidth;

      BlockEntry(Candidate candidate) {
        this.candidate = candidate;
        this.nameWidth = font.width(candidate.name());
        this.idWidth = font.width(candidate.id());
      }

      @Override
      public void extractContent(
          GuiGraphicsExtractor graphics,
          int mouseX,
          int mouseY,
          boolean hovered,
          float partialTick) {
        int x = getContentX();
        graphics.item(candidate.icon(), x, getContentY() + (getContentHeight() - ICON_SIZE) / 2);
        int y = getContentY() + (getContentHeight() - font.lineHeight) / 2 + 1;
        int textX = x + ICON_SIZE + GAP;
        graphics.text(font, candidate.name(), textX, y, NAME_COLOR);
        int idX = textX + nameWidth + GAP;
        if (idX + idWidth <= getContentRight()) {
          graphics.text(font, candidate.id(), idX, y, ID_COLOR);
        }
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        pick(candidate);
        return true;
      }

      @Override
      public Component getNarration() {
        return Component.literal(candidate.name());
      }
    }
  }
}
