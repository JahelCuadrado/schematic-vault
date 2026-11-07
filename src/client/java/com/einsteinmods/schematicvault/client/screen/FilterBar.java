package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.cache.LoadedCatalog;
import com.einsteinmods.schematicvault.core.catalog.CatalogFilter;
import com.einsteinmods.schematicvault.core.catalog.CategoryRow;
import com.einsteinmods.schematicvault.core.catalog.CategoryTree;
import com.einsteinmods.schematicvault.core.catalog.Difficulty;
import com.einsteinmods.schematicvault.core.catalog.SizeBucket;
import com.einsteinmods.schematicvault.core.catalog.SortOrder;
import com.einsteinmods.schematicvault.core.catalog.WidthBucket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The vault's top bar: the search box with its magnifier, the "Category ▾", "Filters ▾" (with the
 * number of chosen fine filters) and sort buttons, and a reserved slot at the right end for one
 * more button ({@link #placeInGearSlot}). Each button opens its {@link DropdownWidget} over the
 * list; the screen hands the open dropdown its clicks, scrolls and Escape first ({@link
 * #clickDropdown}, {@link #scrollDropdown}, {@link #closeDropdown}) and draws it last ({@link
 * #extractDropdown}). The "Filters" dropdown holds "Verified only" and one collapsible section per
 * multi-choice filter: collapsed, it sums up what is chosen ("Difficulty: Easy, Hard ▸"); expanded,
 * its check boxes follow it, indented. The "Category" dropdown is a folded tree: an arrow expands
 * or collapses a category, its name chooses it; it opens with the path to the chosen category
 * expanded. Which sections and categories are open lasts as long as the bar. In a narrow window the
 * search box takes the first row and the buttons a second one.
 *
 * <p>The bar lives as long as the screen; its widgets are made again on each {@code init} (a
 * resize, a return from a sub-screen, a new catalog), and an open dropdown opens again at the same
 * scroll. Cost: nothing per frame but the vanilla widgets and the magnifier sprite; labels change
 * only when the filter does; the category rows (with their item icons) are built once per catalog
 * and folded again only when a category is expanded or collapsed, the "Filters" rows (with their
 * summaries) when the filter or the open sections change.
 */
final class FilterBar {
  static final int HEIGHT = Button.DEFAULT_HEIGHT;

  private static final Identifier SEARCH_SPRITE = Identifier.withDefaultNamespace("icon/search");
  private static final int SEARCH_ICON_SIZE = 12;
  private static final int SEARCH_ICON_SLOT = SEARCH_ICON_SIZE + 4;

  private static final int GAP = 4;
  private static final int MIN_SEARCH_WIDTH = 80;
  private static final int CATEGORY_WIDTH = 96;
  private static final int FILTERS_WIDTH = 88;
  private static final int SORT_WIDTH = 88;

  /** The slot at the right end: a square vanilla button. */
  private static final int GEAR_SIZE = Button.DEFAULT_HEIGHT;

  /** Longest query typed; titles are rarely longer. */
  private static final int MAX_QUERY_LENGTH = 64;

  private static final String EXPANDED = DropdownWidget.EXPANDED_ARROW;
  private static final String COLLAPSED = DropdownWidget.COLLAPSED_ARROW;
  private static final String ARROW = " " + EXPANDED;
  private static final String LESS_THAN = "< ";
  private static final String RANGE = "–";
  private static final String OR_MORE = "+";
  private static final String BLOCK_COUNT_FORMAT = "%,d";

  private static final Component SEARCH = Component.translatable("schematicvault.search");
  private static final Component SEARCH_HINT =
      Component.translatable("schematicvault.search.hint").withStyle(EditBox.SEARCH_HINT_STYLE);
  private static final Component CATEGORY = Component.translatable("schematicvault.category");
  private static final Component ALL_CATEGORIES =
      Component.translatable("schematicvault.category.all");
  private static final Component FILTERS = Component.translatable("schematicvault.filters");
  private static final Component SORT = Component.translatable("schematicvault.sort");
  static final Component DIFFICULTY = Component.translatable("schematicvault.filter.difficulty");
  static final Component VERIFIED = Component.translatable("schematicvault.filter.verified");
  static final Component SIZE = Component.translatable("schematicvault.filter.size");
  static final Component WIDTH = Component.translatable("schematicvault.filter.width");
  private static final Component ANY = Component.translatable("schematicvault.filter.any");

  /** Indentation of a section's options under its row. */
  private static final int OPTION_DEPTH = 1;

  private static final SortOrder[] SORT_ORDERS = SortOrder.values();
  private static final Section[] SECTIONS = Section.values();

  private final Minecraft minecraft;
  private final Font font;
  private final Consumer<CatalogFilter> onChange;
  private final List<DropdownWidget.Row> sortRows;
  private final Set<Section> expandedSections = EnumSet.noneOf(Section.class);
  private final Set<String> expandedCategories = new HashSet<>();

  private CatalogFilter filter;
  private @Nullable LoadedCatalog catalog;
  private List<DropdownWidget.Row> allCategoryRows = List.of();
  private @Nullable LoadedCatalog categoryRowsSource;
  private List<DropdownWidget.Row> categoryRows = List.of();
  private String[] categoryPaths = new String[0];
  private @Nullable Set<String> categoryRowsExpanded;
  private List<DropdownWidget.Row> filterRows = List.of();
  private FineOption[] filterOptions = new FineOption[0];
  private @Nullable CatalogFilter filterRowsFilter;
  private Set<Section> filterRowsExpanded = Set.of();

  private @Nullable EditBox searchBox;
  private @Nullable Button categoryButton;
  private @Nullable Button filtersButton;
  private @Nullable Button sortButton;
  private ScreenRectangle gearSlot = ScreenRectangle.empty();
  private int searchIconX;
  private int searchIconY;
  private int bottom;
  private int screenWidth;
  private int screenHeight;
  private int margin;

  private @Nullable Menu openMenu;
  private @Nullable DropdownWidget dropdown;

  /**
   * @param onChange called with every new filter chosen here or typed in the search box
   */
  FilterBar(
      Minecraft minecraft, Font font, CatalogFilter filter, Consumer<CatalogFilter> onChange) {
    this.minecraft = minecraft;
    this.font = font;
    this.filter = filter;
    this.onChange = onChange;
    List<DropdownWidget.Row> sorts = new ArrayList<>();
    for (SortOrder order : SORT_ORDERS) {
      sorts.add(DropdownWidget.Row.choice(sortName(order).getString(), "", ItemStack.EMPTY, 0));
    }
    this.sortRows = List.copyOf(sorts);
  }

  /**
   * Makes the bar's widgets for this layout; add them with {@link #visitWidgets}.
   *
   * @return the bar's bottom edge
   */
  int build(
      int left,
      int top,
      int width,
      @Nullable LoadedCatalog shown,
      int screenWidth,
      int screenHeight,
      int margin) {
    this.catalog = shown;
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
    this.margin = margin;
    int buttonsWidth = CATEGORY_WIDTH + FILTERS_WIDTH + SORT_WIDTH + GEAR_SIZE + GAP * 4;
    int searchWidth = width - SEARCH_ICON_SLOT - buttonsWidth;
    int buttonsTop = top;
    int buttonsLeft = left + SEARCH_ICON_SLOT + searchWidth + GAP;
    int categoryWidth = CATEGORY_WIDTH;
    int filtersWidth = FILTERS_WIDTH;
    int sortWidth = SORT_WIDTH;
    if (searchWidth < MIN_SEARCH_WIDTH) {
      // Too narrow for one row: search on its own row, the buttons share the one below.
      searchWidth = width - SEARCH_ICON_SLOT;
      buttonsTop = top + HEIGHT + GAP;
      buttonsLeft = left;
      int shared = width - GEAR_SIZE - GAP * 3;
      categoryWidth = shared / 3;
      filtersWidth = shared / 3;
      sortWidth = shared - categoryWidth - filtersWidth;
    }
    searchIconX = left;
    searchIconY = top + (HEIGHT - SEARCH_ICON_SIZE) / 2;
    EditBox oldSearch = searchBox;
    EditBox search = new EditBox(font, left + SEARCH_ICON_SLOT, top, searchWidth, HEIGHT, SEARCH);
    search.setMaxLength(MAX_QUERY_LENGTH);
    search.setHint(SEARCH_HINT);
    search.setValue(filter.text());
    if (oldSearch != null) {
      search.moveCursorTo(oldSearch.getCursorPosition(), false);
    }
    search.setResponder(this::onTextChanged);
    searchBox = search;

    Button category =
        Button.builder(withArrow(CATEGORY), button -> openCategories())
            .bounds(buttonsLeft, buttonsTop, categoryWidth, HEIGHT)
            .build();
    category.active = shown != null;
    categoryButton = category;
    int filtersLeft = buttonsLeft + categoryWidth + GAP;
    filtersButton =
        Button.builder(filtersLabel(), button -> open(Menu.FILTERS))
            .bounds(filtersLeft, buttonsTop, filtersWidth, HEIGHT)
            .build();
    int sortLeft = filtersLeft + filtersWidth + GAP;
    sortButton =
        Button.builder(sortLabel(), button -> open(Menu.SORT))
            .bounds(sortLeft, buttonsTop, sortWidth, HEIGHT)
            .tooltip(Tooltip.create(SORT))
            .build();
    gearSlot = new ScreenRectangle(sortLeft + sortWidth + GAP, buttonsTop, GEAR_SIZE, HEIGHT);
    bottom = buttonsTop + HEIGHT;

    DropdownWidget old = dropdown;
    Menu menu = openMenu;
    dropdown = null;
    openMenu = null;
    if (menu != null && (menu != Menu.CATEGORY || shown != null)) {
      openAtScroll(menu, old);
    }
    return bottom;
  }

  void visitWidgets(Consumer<AbstractWidget> visitor) {
    if (searchBox != null) {
      visitor.accept(searchBox);
    }
    if (categoryButton != null) {
      visitor.accept(categoryButton);
    }
    if (filtersButton != null) {
      visitor.accept(filtersButton);
    }
    if (sortButton != null) {
      visitor.accept(sortButton);
    }
  }

  @Nullable EditBox searchBox() {
    return searchBox;
  }

  /**
   * Puts {@code widget} in the square slot reserved at the bar's right end (for a settings button);
   * call it in the screen's {@code init} after {@link #build}, then add the widget.
   */
  <T extends AbstractWidget> T placeInGearSlot(T widget) {
    widget.setRectangle(gearSlot.width(), gearSlot.height(), gearSlot.left(), gearSlot.top());
    return widget;
  }

  /** The filter changed elsewhere (a chip, a new catalog): labels and search text follow. */
  void update(CatalogFilter newFilter) {
    filter = newFilter;
    EditBox search = searchBox;
    if (search != null && !search.getValue().equals(newFilter.text())) {
      search.setValue(newFilter.text());
    }
    if (filtersButton != null) {
      filtersButton.setMessage(filtersLabel());
    }
    if (sortButton != null) {
      sortButton.setMessage(sortLabel());
    }
  }

  private void onTextChanged(String text) {
    if (!text.equals(filter.text())) {
      change(filter.withText(text));
    }
  }

  private void change(CatalogFilter newFilter) {
    update(newFilter);
    onChange.accept(newFilter);
  }

  private void open(Menu menu) {
    Button anchor =
        switch (menu) {
          case CATEGORY -> categoryButton;
          case FILTERS -> filtersButton;
          case SORT -> sortButton;
        };
    if (anchor == null) {
      return;
    }
    int shownRow = -1;
    if (menu == Menu.FILTERS) {
      buildFilterRows();
    }
    if (menu == Menu.CATEGORY) {
      LoadedCatalog shown = catalog;
      if (shown == null) {
        return;
      }
      buildCategoryRows(shown);
      for (int index = 0; index < categoryPaths.length; index++) {
        if (categoryPaths[index].equals(filter.categoryPath())) {
          shownRow = index;
        }
      }
    }
    List<DropdownWidget.Row> rows =
        switch (menu) {
          case CATEGORY -> categoryRows;
          case FILTERS -> filterRows;
          case SORT -> sortRows;
        };
    List<DropdownWidget.Row> measured = menu == Menu.CATEGORY ? allCategoryRows : rows;
    DropdownWidget.Choices choices =
        switch (menu) {
          case CATEGORY -> new CategoryChoices();
          case FILTERS -> new FilterChoices();
          case SORT -> new SortChoices();
        };
    DropdownWidget opened =
        new DropdownWidget(
            font,
            rows,
            measured,
            choices,
            anchor.getX(),
            anchor.getBottom() + 1,
            anchor.getWidth(),
            screenWidth,
            screenHeight,
            margin);
    if (shownRow >= 0) {
      opened.scrollTo(shownRow);
    }
    dropdown = opened;
    openMenu = menu;
  }

  /**
   * Opens the "Category" dropdown from its button: the categories above the chosen one expand
   * (added to those the player expanded), so the chosen row shows, scrolled into view.
   */
  private void openCategories() {
    expandedCategories.addAll(CategoryTree.ancestors(filter.categoryPath()));
    open(Menu.CATEGORY);
  }

  /** Expands or collapses the category {@code path}; the dropdown grows or shrinks in place. */
  private void toggleCategory(String path) {
    if (!expandedCategories.remove(path)) {
      expandedCategories.add(path);
    }
    openAtScroll(Menu.CATEGORY, dropdown);
  }

  /** Opens {@code menu} again (new rows, a new size) at the scroll {@code old} had. */
  private void openAtScroll(Menu menu, @Nullable DropdownWidget old) {
    open(menu);
    DropdownWidget reopened = dropdown;
    if (reopened != null && old != null) {
      reopened.setScroll(old.scroll());
    }
  }

  /** Expands or collapses {@code section}; the dropdown grows or shrinks in place. */
  private void toggleSection(Section section) {
    if (!expandedSections.remove(section)) {
      expandedSections.add(section);
    }
    openAtScroll(Menu.FILTERS, dropdown);
  }

  /**
   * The "Filters" rows: "Verified only", then each section's row and, when it is expanded, its
   * options. Made again only when the filter (the summaries) or the expanded sections changed.
   */
  private void buildFilterRows() {
    if (filter.equals(filterRowsFilter) && expandedSections.equals(filterRowsExpanded)) {
      return;
    }
    List<DropdownWidget.Row> rows = new ArrayList<>();
    List<FineOption> options = new ArrayList<>();
    rows.add(DropdownWidget.Row.check(VERIFIED.getString(), "", 0));
    options.add(new VerifiedOption());
    for (Section section : SECTIONS) {
      boolean expanded = expandedSections.contains(section);
      rows.add(
          DropdownWidget.Row.choice(
              sectionLabel(section, expanded),
              expanded ? EXPANDED : COLLAPSED,
              ItemStack.EMPTY,
              0));
      options.add(new SectionOption(section));
      if (expanded) {
        addSectionOptions(section, rows, options);
      }
    }
    filterRows = List.copyOf(rows);
    filterOptions = options.toArray(FineOption[]::new);
    filterRowsFilter = filter;
    filterRowsExpanded = EnumSet.copyOf(expandedSections);
  }

  private static void addSectionOptions(
      Section section, List<DropdownWidget.Row> rows, List<FineOption> options) {
    switch (section) {
      case DIFFICULTY -> {
        for (Difficulty difficulty : Difficulty.values()) {
          rows.add(
              DropdownWidget.Row.check(difficultyName(difficulty).getString(), "", OPTION_DEPTH));
          options.add(new DifficultyOption(difficulty));
        }
      }
      case SIZE -> {
        for (SizeBucket size : SizeBucket.values()) {
          rows.add(
              DropdownWidget.Row.check(sizeName(size).getString(), sizeRange(size), OPTION_DEPTH));
          options.add(new SizeOption(size));
        }
      }
      case WIDTH -> {
        for (WidthBucket width : WidthBucket.values()) {
          rows.add(DropdownWidget.Row.check(width.label(), "", OPTION_DEPTH));
          options.add(new WidthOption(width));
        }
      }
    }
  }

  /** Expanded: the section's name; collapsed: "name: chosen, chosen" or "name: All". */
  private String sectionLabel(Section section, boolean expanded) {
    Component name =
        switch (section) {
          case DIFFICULTY -> DIFFICULTY;
          case SIZE -> SIZE;
          case WIDTH -> WIDTH;
        };
    if (expanded) {
      return name.getString();
    }
    List<String> chosen = new ArrayList<>();
    switch (section) {
      case DIFFICULTY -> {
        for (Difficulty difficulty : Difficulty.values()) {
          if (filter.difficulties().contains(difficulty)) {
            chosen.add(difficultyName(difficulty).getString());
          }
        }
      }
      case SIZE -> {
        for (SizeBucket size : SizeBucket.values()) {
          if (filter.sizes().contains(size)) {
            chosen.add(sizeName(size).getString());
          }
        }
      }
      case WIDTH -> {
        for (WidthBucket width : WidthBucket.values()) {
          if (filter.widths().contains(width)) {
            chosen.add(width.label());
          }
        }
      }
    }
    return FilterChips.group(name, chosen.isEmpty() ? List.of(ANY.getString()) : chosen);
  }

  /**
   * The category rows shown: every row of {@code shown} with its icon and arrow is made once per
   * catalog; the rows under collapsed categories are left out again only when the catalog or the
   * expanded categories changed.
   */
  private void buildCategoryRows(LoadedCatalog shown) {
    List<CategoryRow> categories = shown.categories();
    if (categoryRowsSource != shown) {
      List<DropdownWidget.Row> rows = new ArrayList<>(categories.size());
      for (int index = 0; index < categories.size(); index++) {
        CategoryRow row = categories.get(index);
        String name = row.isAll() ? ALL_CATEGORIES.getString() : row.name();
        DropdownWidget.Branch branch =
            CategoryTree.hasChildren(categories, index)
                ? DropdownWidget.Branch.COLLAPSED
                : DropdownWidget.Branch.LEAF;
        rows.add(
            DropdownWidget.Row.branch(
                name,
                Integer.toString(row.count()),
                CategoryIcons.icon(row.path()),
                row.depth(),
                branch));
      }
      allCategoryRows = List.copyOf(rows);
      categoryRowsSource = shown;
      categoryRowsExpanded = null;
    }
    if (expandedCategories.equals(categoryRowsExpanded)) {
      return;
    }
    int[] visible = CategoryTree.visible(categories, expandedCategories);
    List<DropdownWidget.Row> rows = new ArrayList<>(visible.length);
    String[] paths = new String[visible.length];
    for (int index = 0; index < visible.length; index++) {
      DropdownWidget.Row row = allCategoryRows.get(visible[index]);
      String path = categories.get(visible[index]).path();
      boolean expanded = row.branch().hasArrow() && expandedCategories.contains(path);
      rows.add(expanded ? row.withBranch(DropdownWidget.Branch.EXPANDED) : row);
      paths[index] = path;
    }
    categoryRows = List.copyOf(rows);
    categoryPaths = paths;
    categoryRowsExpanded = Set.copyOf(expandedCategories);
  }

  boolean isDropdownOpen() {
    return dropdown != null;
  }

  boolean isOverDropdown(double mouseX, double mouseY) {
    DropdownWidget open = dropdown;
    return open != null && open.isMouseOver(mouseX, mouseY);
  }

  /** Closes the open dropdown, if any; returns whether one was open (Escape then stops here). */
  boolean closeDropdown() {
    boolean wasOpen = dropdown != null;
    dropdown = null;
    openMenu = null;
    return wasOpen;
  }

  /**
   * Gives a click to the open dropdown before the screen's widgets. Inside, it picks an option; a
   * single choice closes the dropdown. Outside, the dropdown closes and the click stops here,
   * unless it lands on another of the bar's buttons, which then opens its own dropdown.
   *
   * @return whether the click was used
   */
  boolean clickDropdown(double mouseX, double mouseY) {
    DropdownWidget open = dropdown;
    if (open == null) {
      return false;
    }
    if (open.isMouseOver(mouseX, mouseY)) {
      if (open.isOptionAt(mouseX, mouseY)) {
        AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
      }
      // A choice can rebuild the screen, which opens the dropdown again: close whichever is open.
      if (open.click(mouseX, mouseY)) {
        closeDropdown();
      }
      return true;
    }
    Menu clicked = menuAt(mouseX, mouseY);
    Menu was = openMenu;
    closeDropdown();
    return clicked == null || clicked == was;
  }

  boolean scrollDropdown(double mouseX, double mouseY, double scrollY) {
    DropdownWidget open = dropdown;
    if (open == null || !open.isMouseOver(mouseX, mouseY)) {
      return false;
    }
    open.scrolled(scrollY);
    return true;
  }

  private @Nullable Menu menuAt(double mouseX, double mouseY) {
    if (isOver(categoryButton, mouseX, mouseY)) {
      return Menu.CATEGORY;
    }
    if (isOver(filtersButton, mouseX, mouseY)) {
      return Menu.FILTERS;
    }
    if (isOver(sortButton, mouseX, mouseY)) {
      return Menu.SORT;
    }
    return null;
  }

  private static boolean isOver(@Nullable Button button, double mouseX, double mouseY) {
    return button != null && button.isMouseOver(mouseX, mouseY);
  }

  /** The magnifier left of the search box. */
  void extractDecorations(GuiGraphicsExtractor graphics) {
    graphics.blitSprite(
        RenderPipelines.GUI_TEXTURED,
        SEARCH_SPRITE,
        searchIconX,
        searchIconY,
        SEARCH_ICON_SIZE,
        SEARCH_ICON_SIZE);
  }

  /** The open dropdown, on a stratum of its own above everything drawn before it. */
  void extractDropdown(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    DropdownWidget open = dropdown;
    if (open != null) {
      graphics.nextStratum();
      open.extract(graphics, mouseX, mouseY);
    }
  }

  private Component filtersLabel() {
    int count = filter.fineFilterCount();
    return withArrow(
        count == 0 ? FILTERS : Component.translatable("schematicvault.filters.count", count));
  }

  private Component sortLabel() {
    return withArrow(sortName(filter.sort()));
  }

  private static Component withArrow(Component label) {
    return Component.empty().append(label).append(ARROW);
  }

  static Component sortName(SortOrder order) {
    return switch (order) {
      case NEWEST -> Component.translatable("schematicvault.sort.newest");
      case SMALLEST -> Component.translatable("schematicvault.sort.smallest");
      case LARGEST -> Component.translatable("schematicvault.sort.largest");
    };
  }

  static Component difficultyName(Difficulty difficulty) {
    return switch (difficulty) {
      case EASY -> Component.translatable("schematicvault.filter.difficulty.easy");
      case MEDIUM -> Component.translatable("schematicvault.filter.difficulty.medium");
      case HARD -> Component.translatable("schematicvault.filter.difficulty.hard");
    };
  }

  static Component sizeName(SizeBucket size) {
    return switch (size) {
      case SMALL -> Component.translatable("schematicvault.filter.size.small");
      case MEDIUM -> Component.translatable("schematicvault.filter.size.medium");
      case LARGE -> Component.translatable("schematicvault.filter.size.large");
      case HUGE -> Component.translatable("schematicvault.filter.size.huge");
    };
  }

  /** The bucket's block counts: {@code < 300}, {@code 300–2,999}, {@code 30,000+}. */
  private static String sizeRange(SizeBucket size) {
    if (size.minBlocks() == 0) {
      return LESS_THAN + count(size.maxBlocksExclusive());
    }
    if (size.maxBlocksExclusive() == Long.MAX_VALUE) {
      return count(size.minBlocks()) + OR_MORE;
    }
    return count(size.minBlocks()) + RANGE + count(size.maxBlocksExclusive() - 1);
  }

  private static String count(long value) {
    return String.format(Locale.ROOT, BLOCK_COUNT_FORMAT, value);
  }

  /** The bar's three dropdowns. */
  private enum Menu {
    CATEGORY,
    FILTERS,
    SORT
  }

  /** The collapsible sections of the "Filters" dropdown, in their order. */
  private enum Section {
    DIFFICULTY,
    SIZE,
    WIDTH
  }

  /** What a row of the "Filters" dropdown toggles. */
  private sealed interface FineOption
      permits VerifiedOption, SectionOption, DifficultyOption, SizeOption, WidthOption {}

  private record VerifiedOption() implements FineOption {}

  /** A section's own row: expands or collapses it. */
  private record SectionOption(Section value) implements FineOption {}

  private record DifficultyOption(Difficulty value) implements FineOption {}

  private record SizeOption(SizeBucket value) implements FineOption {}

  private record WidthOption(WidthBucket value) implements FineOption {}

  private final class CategoryChoices implements DropdownWidget.Choices {
    @Override
    public boolean isChecked(int row) {
      return categoryPaths[row].equals(filter.categoryPath());
    }

    @Override
    public boolean choose(int row) {
      if (!isChecked(row)) {
        change(filter.withCategory(categoryPaths[row]));
      }
      return true;
    }

    @Override
    public void toggle(int row) {
      toggleCategory(categoryPaths[row]);
    }
  }

  /** Check boxes and section rows: the dropdown stays open to choose several. */
  private final class FilterChoices implements DropdownWidget.Choices {
    @Override
    public boolean isChecked(int row) {
      return switch (filterOptions[row]) {
        case VerifiedOption _ -> filter.verifiedOnly();
        case SectionOption _ -> false;
        case DifficultyOption option -> filter.difficulties().contains(option.value());
        case SizeOption option -> filter.sizes().contains(option.value());
        case WidthOption option -> filter.widths().contains(option.value());
      };
    }

    @Override
    public boolean choose(int row) {
      switch (filterOptions[row]) {
        case VerifiedOption _ -> change(filter.withVerifiedOnly(!filter.verifiedOnly()));
        case SectionOption section -> toggleSection(section.value());
        case DifficultyOption difficulty -> change(filter.toggled(difficulty.value()));
        case SizeOption size -> change(filter.toggled(size.value()));
        case WidthOption width -> change(filter.toggled(width.value()));
      }
      return false;
    }
  }

  private final class SortChoices implements DropdownWidget.Choices {
    @Override
    public boolean isChecked(int row) {
      return SORT_ORDERS[row] == filter.sort();
    }

    @Override
    public boolean choose(int row) {
      if (!isChecked(row)) {
        change(filter.withSort(SORT_ORDERS[row]));
      }
      return true;
    }
  }
}
