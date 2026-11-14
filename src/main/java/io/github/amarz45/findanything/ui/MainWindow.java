package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.agent.SearchTool;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexerState;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * The one window: a Search/Ask switch and the input field, the search folders as chips with an "Add
 * folder" button (F01), a "Filters" button beside them that opens the filter row (F04), the results
 * table on the left, the detail pane on the right, and the index status at the bottom (F03). Errors
 * appear in a bar under the toolbar. The window only displays data and forwards the user's actions
 * to the callbacks {@link GuiController} registers.
 */
public final class MainWindow extends BorderPane {

    static final String TITLE = "Find Anything";
    static final String READ_ONLY_STATUS =
            "Read-only: another Find Anything process is managing the index.";
    static final String NO_FOLDERS = "No search folders yet. Click “Add folder”.";
    static final String SEARCH_PROMPT = "Search your files";
    static final String ASK_PROMPT = "Ask a question about your files, then press Enter";
    static final String NO_CONVERSATIONS = "No saved conversations yet";
    static final String FILTERS = "Filters";
    static final String ANY_TYPE = "Any type";
    static final String ANYWHERE = "Anywhere";

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter CONVERSATION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final int CONVERSATION_LABEL_CHARS = 60;

    private final ResultsView resultsView = new ResultsView();
    private final DetailPane detailPane = new DetailPane();
    private final ToggleButton searchToggle = new ToggleButton("Search");
    private final ToggleButton askToggle = new ToggleButton("Ask");
    private final TextField input = new TextField();
    private final Button newConversation = new Button("New conversation");
    private final MenuButton conversations = new MenuButton("Conversations");
    private final Button addFolder = new Button("Add folder…");
    private final Button settings = new Button("Settings…");
    private final SettingsDialog settingsDialog = new SettingsDialog();
    private final FlowPane folderChips = new FlowPane(6, 6);
    private final ToggleButton filtersToggle = new ToggleButton(FILTERS);
    private final FlowPane filterRow = new FlowPane(8, 6);
    private final MenuButton typeFilter = new MenuButton(ANY_TYPE);
    private final DatePicker modifiedFrom = new DatePicker();
    private final DatePicker modifiedTo = new DatePicker();
    private final TextField minSize = new TextField();
    private final TextField maxSize = new TextField();
    private final MenuButton locationFilter = new MenuButton(ANYWHERE);
    private final Button clearFilters = new Button("Clear filters");
    private final HBox errorBar = new HBox(8);
    private final Label errorLabel = new Label();
    private final Label statusLabel = new Label();

    private SearchMode mode = SearchMode.SEARCH;
    private boolean readOnly;
    private List<SearchFolder> folders = List.of();
    private Set<Path> localOnlyFolders = Set.of();

    /** The folder the location filter is set to, or null for anywhere. */
    private Path filterLocation;

    /** The filters last reported, so an edit that changes nothing doesn't search again. */
    private SearchFilters sentFilters = SearchFilters.NONE;

    /** True while "Clear filters" empties the controls, so they report once at the end. */
    private boolean resettingFilters;

    /** True while the error bar shows a problem with the filters, which fixing them removes. */
    private boolean filterErrorShown;

    private Consumer<SearchFilters> onFiltersChanged = searchFilters -> {};
    private Consumer<String> onQueryChanged = query -> {};
    private Consumer<String> onAskSubmitted = request -> {};
    private Runnable onNewConversation = () -> {};
    private Consumer<String> onOpenConversation = id -> {};
    private Runnable onConversationsRequested = () -> {};
    private Consumer<Path> onAddFolder = folder -> {};
    private Consumer<Path> onRemoveFolder = folder -> {};
    private BiConsumer<Path, Boolean> onSetLocalOnly = (folder, localOnly) -> {};

    public MainWindow() {
        getStylesheets().add(Styles.STYLESHEET);

        ToggleGroup modes = new ToggleGroup();
        searchToggle.setToggleGroup(modes);
        askToggle.setToggleGroup(modes);
        searchToggle.setSelected(true);
        modes.selectedToggleProperty()
                .addListener(
                        (observable, previous, selected) -> {
                            if (selected == null) {
                                previous.setSelected(true); // one mode is always on
                            } else if (selected == askToggle && mode != SearchMode.ASK) {
                                setMode(SearchMode.ASK);
                            } else if (selected == searchToggle && mode != SearchMode.SEARCH) {
                                setMode(SearchMode.SEARCH);
                            }
                        });

        input.getStyleClass().add("query-input");
        input.textProperty()
                .addListener(
                        (observable, previous, text) -> {
                            if (mode == SearchMode.SEARCH) {
                                onQueryChanged.accept(text);
                            }
                        });
        input.setOnAction(
                event -> {
                    if (mode == SearchMode.ASK && !input.getText().isBlank()) {
                        String request = input.getText();
                        input.clear();
                        onAskSubmitted.accept(request);
                    }
                });
        HBox.setHgrow(input, Priority.ALWAYS);

        newConversation.getStyleClass().add("new-conversation");
        newConversation.setOnAction(event -> onNewConversation.run());
        conversations.getStyleClass().add("conversations");
        // The list is refreshed as the menu opens, so conversations saved by the CLI show up too.
        conversations.setOnShowing(event -> onConversationsRequested.run());
        showConversations(List.of());
        addFolder.getStyleClass().add("add-folder");
        settings.getStyleClass().add("settings");
        settings.setOnAction(
                event -> {
                    if (!settingsDialog.isShowing()) {
                        settingsDialog.show();
                    }
                });
        addFolder.setOnAction(event -> chooseFolder());

        HBox toolbar =
                new HBox(
                        8,
                        searchToggle,
                        askToggle,
                        input,
                        newConversation,
                        conversations,
                        addFolder,
                        settings);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        folderChips.getStyleClass().add("folder-chips");
        folderChips.setPadding(new Insets(0, 8, 8, 8));
        folderChips.setAlignment(Pos.CENTER_LEFT);
        buildFilterRow();

        errorLabel.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button dismiss = new Button("✕");
        dismiss.getStyleClass().add("dismiss-error");
        dismiss.setOnAction(event -> hideError());
        errorBar.getStyleClass().add("error-bar");
        errorBar.setAlignment(Pos.CENTER_LEFT);
        errorBar.getChildren().addAll(errorLabel, spacer, dismiss);
        hideError();

        setTop(new VBox(toolbar, folderChips, filterRow, errorBar));

        SplitPane split = new SplitPane(resultsView, detailPane);
        split.setDividerPositions(0.55);
        setCenter(split);

        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        setBottom(statusLabel);

        resultsView.setOnTryAsk(() -> setMode(SearchMode.ASK));
        showFolders(List.of());
        applyMode();
    }

    /** Puts the window on the stage and shows it. */
    public void show(Stage stage) {
        stage.setTitle(TITLE);
        stage.setScene(new Scene(this, 1200, 750));
        settingsDialog.initOwner(stage);
        stage.show();
        input.requestFocus();
    }

    // ------------------------------------------------------------------ what the controller shows

    /**
     * Switches between instant search and Ask mode. Back in Search mode, the field's text is
     * searched again so the table shows search results instead of an answer's files.
     */
    public void setMode(SearchMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        applyMode();
        if (mode == SearchMode.ASK) {
            detailPane.showConversation();
        } else {
            onQueryChanged.accept(input.getText());
        }
    }

    private void applyMode() {
        boolean ask = mode == SearchMode.ASK;
        askToggle.setSelected(ask);
        searchToggle.setSelected(!ask);
        input.setPromptText(ask ? ASK_PROMPT : SEARCH_PROMPT);
        newConversation.setVisible(ask);
        newConversation.setManaged(ask);
        conversations.setVisible(ask);
        conversations.setManaged(ask);
        // The filters belong to instant search; in Ask mode the agent sets its own.
        filtersToggle.setVisible(!ask);
        filtersToggle.setManaged(!ask);
        boolean rowShown = !ask && filtersToggle.isSelected();
        filterRow.setVisible(rowShown);
        filterRow.setManaged(rowShown);
    }

    /** Disables the Ask controls while a request runs; a conversation takes one at a time. */
    public void setAskBusy(boolean busy) {
        input.setDisable(busy);
        newConversation.setDisable(busy);
        conversations.setDisable(busy);
        if (!busy) {
            input.requestFocus();
        }
    }

    public SearchMode getMode() {
        return mode;
    }

    /** Shows the search folders as chips; each chip's menu can remove its folder. */
    public void showFolders(List<SearchFolder> searchFolders) {
        folders = List.copyOf(Objects.requireNonNull(searchFolders, "searchFolders"));
        folderChips.getChildren().clear();
        if (folders.isEmpty()) {
            Label none = new Label(NO_FOLDERS);
            none.getStyleClass().add("detail-message");
            folderChips.getChildren().add(none);
        }
        for (SearchFolder folder : folders) {
            folderChips.getChildren().add(chip(folder));
        }
        folderChips.getChildren().add(filtersToggle);
        showLocations();
    }

    private MenuButton chip(SearchFolder folder) {
        Path path = folder.getPath();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        String suffix = "";
        if (!folder.isAvailable()) {
            suffix = " (unavailable)";
        } else if (folder.isPaused()) {
            suffix = " (paused)";
        }
        boolean localOnly = localOnlyFolders.contains(path);
        boolean localOnlyInside =
                !localOnly && localOnlyFolders.stream().anyMatch(marked -> marked.startsWith(path));
        if (localOnly) {
            suffix += " (local model only)";
        } else if (localOnlyInside) {
            suffix += " (local model only inside)";
        }
        MenuButton chip = new MenuButton(name + suffix);
        chip.getStyleClass().add("chip");
        if (!folder.isAvailable()) {
            chip.getStyleClass().add("unavailable");
        }
        if (localOnly || localOnlyInside) {
            chip.getStyleClass().add("local-only");
        }
        chip.setTooltip(new Tooltip(path.toString()));
        CheckMenuItem localOnlyItem = new CheckMenuItem("Local model only");
        localOnlyItem.getStyleClass().add("local-only-item");
        localOnlyItem.setSelected(localOnly);
        localOnlyItem.setDisable(readOnly);
        localOnlyItem.setOnAction(event -> onSetLocalOnly.accept(path, localOnlyItem.isSelected()));
        MenuItem remove = new MenuItem("Remove");
        remove.setDisable(readOnly);
        remove.setOnAction(event -> onRemoveFolder.accept(path));
        chip.getItems().addAll(localOnlyItem, remove);
        return chip;
    }

    // ------------------------------------------------------------------ filters (F04)

    private void buildFilterRow() {
        filtersToggle.getStyleClass().add("filters-toggle");
        filtersToggle.selectedProperty().addListener((observable, was, selected) -> applyMode());

        typeFilter.getStyleClass().add("type-filter");
        for (String type : SearchTool.FILE_TYPES) {
            CheckMenuItem item = new CheckMenuItem(type);
            item.selectedProperty().addListener((observable, was, selected) -> filtersEdited());
            typeFilter.getItems().add(item);
        }

        modifiedFrom.getStyleClass().add("modified-from");
        modifiedTo.getStyleClass().add("modified-to");
        for (DatePicker picker : List.of(modifiedFrom, modifiedTo)) {
            picker.setPrefWidth(140);
            picker.setPromptText("yyyy-mm-dd");
            picker.valueProperty().addListener((observable, previous, day) -> filtersEdited());
        }

        minSize.getStyleClass().add("min-size");
        maxSize.getStyleClass().add("max-size");
        for (TextField size : List.of(minSize, maxSize)) {
            size.setPrefWidth(80);
            size.setPromptText("1MB");
            size.setOnAction(event -> filtersEdited());
            size.focusedProperty()
                    .addListener(
                            (observable, was, focused) -> {
                                if (!focused) {
                                    filtersEdited();
                                }
                            });
        }

        locationFilter.getStyleClass().add("location-filter");
        clearFilters.getStyleClass().add("clear-filters");
        clearFilters.setOnAction(event -> clearFilters());

        filterRow.getStyleClass().add("filter-row");
        filterRow.setPadding(new Insets(0, 8, 8, 8));
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow
                .getChildren()
                .addAll(
                        new Label("Type"),
                        typeFilter,
                        new Label("Modified from"),
                        modifiedFrom,
                        new Label("to"),
                        modifiedTo,
                        new Label("Size from"),
                        minSize,
                        new Label("to"),
                        maxSize,
                        new Label("In"),
                        locationFilter,
                        clearFilters);
    }

    /** Fills the location filter's menu: anywhere, each search folder, or a folder inside one. */
    private void showLocations() {
        locationFilter.getItems().clear();
        MenuItem anywhere = new MenuItem(ANYWHERE);
        anywhere.setOnAction(event -> setFilterLocation(null));
        locationFilter.getItems().add(anywhere);
        for (SearchFolder folder : folders) {
            MenuItem item = new MenuItem(folder.getPath().toString());
            item.setOnAction(event -> setFilterLocation(folder.getPath()));
            locationFilter.getItems().add(item);
        }
        MenuItem choose = new MenuItem("Choose a folder…");
        choose.setOnAction(event -> chooseLocation());
        locationFilter.getItems().add(choose);
        // A location whose search folder was removed would only filter everything out.
        if (filterLocation != null
                && folders.stream()
                        .noneMatch(folder -> filterLocation.startsWith(folder.getPath()))) {
            setFilterLocation(null);
        }
    }

    private void setFilterLocation(Path location) {
        filterLocation = location;
        if (location == null) {
            locationFilter.setText(ANYWHERE);
            locationFilter.setTooltip(null);
        } else {
            Path name = location.getFileName();
            locationFilter.setText(name == null ? location.toString() : name.toString());
            locationFilter.setTooltip(new Tooltip(location.toString()));
        }
        filtersEdited();
    }

    private void clearFilters() {
        resettingFilters = true;
        try {
            typeFilter.getItems().forEach(item -> ((CheckMenuItem) item).setSelected(false));
            modifiedFrom.setValue(null);
            modifiedTo.setValue(null);
            minSize.clear();
            maxSize.clear();
            setFilterLocation(null);
        } finally {
            resettingFilters = false;
        }
        filtersEdited();
    }

    /**
     * Reads the controls and reports the filters if they changed. A size that can't be read is
     * shown as an error and nothing is reported; whether the filters make sense together is the
     * search's check (the controller shows what it finds).
     */
    private void filtersEdited() {
        if (resettingFilters) {
            return;
        }
        List<String> types =
                typeFilter.getItems().stream()
                        .filter(item -> ((CheckMenuItem) item).isSelected())
                        .map(MenuItem::getText)
                        .toList();
        SearchFilters searchFilters;
        try {
            searchFilters =
                    new SearchFilters(
                            Set.copyOf(types),
                            modifiedFrom.getValue(),
                            modifiedTo.getValue(),
                            sizeIn(minSize),
                            sizeIn(maxSize),
                            filterLocation);
        } catch (IllegalArgumentException e) {
            showFilterError(e.getMessage());
            return;
        }
        if (searchFilters.equals(sentFilters) && !filterErrorShown) {
            return;
        }
        sentFilters = searchFilters;
        clearFilterError();
        typeFilter.setText(types.isEmpty() ? ANY_TYPE : String.join(", ", types));
        int active = 0;
        if (!types.isEmpty()) {
            active++;
        }
        if (searchFilters.modifiedAfter() != null || searchFilters.modifiedBefore() != null) {
            active++;
        }
        if (searchFilters.minSizeBytes() >= 0 || searchFilters.maxSizeBytes() >= 0) {
            active++;
        }
        if (searchFilters.location() != null) {
            active++;
        }
        filtersToggle.setText(active == 0 ? FILTERS : FILTERS + " (" + active + ")");
        onFiltersChanged.accept(searchFilters);
    }

    private static long sizeIn(TextField field) {
        return field.getText().isBlank() ? -1 : CliApp.parseSize(field.getText());
    }

    /**
     * Shows a problem with the filters in the error bar. Unlike other errors it doesn't wait to be
     * dismissed: {@link #clearFilterError()} removes it once the filters are changed.
     */
    public void showFilterError(String message) {
        showError(message);
        filterErrorShown = true;
    }

    /** Removes the filters' error, if that is what the error bar shows. */
    public void clearFilterError() {
        if (filterErrorShown) {
            hideError();
        }
    }

    /** Marks the chips of the "local model only" folders (F14), and of those with one inside. */
    public void showLocalOnlyFolders(Set<Path> localOnlyFolders) {
        this.localOnlyFolders = Set.copyOf(Objects.requireNonNull(localOnlyFolders, "folders"));
        showFolders(folders);
    }

    /** Fills the "Conversations" menu with the saved conversations, most recent first (F12). */
    public void showConversations(List<Conversation> savedConversations) {
        Objects.requireNonNull(savedConversations, "savedConversations");
        conversations.getItems().clear();
        if (savedConversations.isEmpty()) {
            MenuItem none = new MenuItem(NO_CONVERSATIONS);
            none.setDisable(true);
            conversations.getItems().add(none);
            return;
        }
        for (Conversation conversation : savedConversations) {
            MenuItem item = new MenuItem(conversationLabel(conversation));
            item.setOnAction(event -> onOpenConversation.accept(conversation.getId()));
            conversations.getItems().add(item);
        }
    }

    /** When the conversation started (from its id) and how it started (its first request). */
    static String conversationLabel(Conversation conversation) {
        String id = conversation.getId();
        String when = id;
        try {
            when = CONVERSATION_TIME.format(LocalDateTime.parse(id.substring(0, 15), ID_TIME));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            // An id of another shape is shown as it is.
        }
        String first =
                conversation.getMessages().stream()
                        .filter(message -> message.role() == Message.Role.USER)
                        .map(Message::content)
                        .findFirst()
                        .orElse("(no request yet)");
        return when + "  " + CliApp.snippet(first, CONVERSATION_LABEL_CHARS);
    }

    /** Shows the index's state; in read-only mode the folder controls are disabled. */
    public void showStatus(IndexStatus status) {
        Objects.requireNonNull(status, "status");
        statusLabel.setText(statusText(status));
        boolean nowReadOnly = status.state() == IndexerState.READ_ONLY;
        if (nowReadOnly != readOnly) {
            readOnly = nowReadOnly;
            addFolder.setDisable(readOnly);
            settingsDialog.setReadOnly(readOnly);
            showFolders(folders);
        }
    }

    /** The status indicator's text (F03). */
    static String statusText(IndexStatus status) {
        String failed = "";
        if (status.failedFiles() > 0) {
            failed =
                    " · "
                            + status.failedFiles()
                            + (status.failedFiles() == 1 ? " file" : " files")
                            + " could not be read";
        }
        return switch (status.state()) {
            case READ_ONLY -> READ_ONLY_STATUS + failed;
            case OPENING -> "Opening the index…";
            case STOPPING, STOPPED -> "Stopping…";
            case IDLE, RECONCILING, INDEXING, EMBEDDING -> {
                if (status.isUpToDate()) {
                    yield "Up to date" + failed;
                }
                String pending =
                        status.pendingFiles() > 1 ? " " + status.pendingFiles() + " pending" : "";
                yield "Indexing…" + pending + failed;
            }
        };
    }

    /** Shows a message in the error bar until the user dismisses it or another one replaces it. */
    public void showError(String message) {
        errorLabel.setText(Objects.requireNonNull(message, "message"));
        filterErrorShown = false;
        errorBar.setVisible(true);
        errorBar.setManaged(true);
    }

    private void hideError() {
        filterErrorShown = false;
        errorBar.setVisible(false);
        errorBar.setManaged(false);
    }

    public ResultsView getResultsView() {
        return resultsView;
    }

    public SettingsDialog getSettingsDialog() {
        return settingsDialog;
    }

    public DetailPane getDetailPane() {
        return detailPane;
    }

    // ------------------------------------------------------------------ the user's actions

    /** Called with the field's text on every change in Search mode. */
    public void setOnQueryChanged(Consumer<String> onQueryChanged) {
        this.onQueryChanged = Objects.requireNonNull(onQueryChanged, "onQueryChanged");
    }

    /** Called with the new filters whenever the user changes one (F04). */
    public void setOnFiltersChanged(Consumer<SearchFilters> onFiltersChanged) {
        this.onFiltersChanged = Objects.requireNonNull(onFiltersChanged, "onFiltersChanged");
    }

    /** Called with the request when Enter is pressed in Ask mode. */
    public void setOnAskSubmitted(Consumer<String> onAskSubmitted) {
        this.onAskSubmitted = Objects.requireNonNull(onAskSubmitted, "onAskSubmitted");
    }

    /** Called when "New conversation" is clicked. */
    public void setOnNewConversation(Runnable onNewConversation) {
        this.onNewConversation = Objects.requireNonNull(onNewConversation, "onNewConversation");
    }

    /** Called with the id of the saved conversation chosen from the "Conversations" menu. */
    public void setOnOpenConversation(Consumer<String> onOpenConversation) {
        this.onOpenConversation = Objects.requireNonNull(onOpenConversation, "onOpenConversation");
    }

    /** Called as the "Conversations" menu opens, so the controller can refresh its list. */
    public void setOnConversationsRequested(Runnable onConversationsRequested) {
        this.onConversationsRequested =
                Objects.requireNonNull(onConversationsRequested, "onConversationsRequested");
    }

    /** Called with the folder the user picked with "Add folder". */
    public void setOnAddFolder(Consumer<Path> onAddFolder) {
        this.onAddFolder = Objects.requireNonNull(onAddFolder, "onAddFolder");
    }

    /** Called with the folder and the new state when a chip's "Local model only" is toggled. */
    public void setOnSetLocalOnly(BiConsumer<Path, Boolean> onSetLocalOnly) {
        this.onSetLocalOnly = Objects.requireNonNull(onSetLocalOnly, "onSetLocalOnly");
    }

    /** Called with the folder whose chip menu "Remove" was chosen. */
    public void setOnRemoveFolder(Consumer<Path> onRemoveFolder) {
        this.onRemoveFolder = Objects.requireNonNull(onRemoveFolder, "onRemoveFolder");
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add a folder to search");
        File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            onAddFolder.accept(chosen.toPath());
        }
    }

    private void chooseLocation() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Search only in this folder");
        if (!folders.isEmpty() && folders.get(0).isAvailable()) {
            chooser.setInitialDirectory(folders.get(0).getPath().toFile());
        }
        File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            setFilterLocation(chosen.toPath());
        }
    }
}
