package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexerState;
import io.github.amarz45.findanything.llm.Message;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
 * folder" button (F01), the results table on the left, the detail pane on the right, and the index
 * status at the bottom (F03). Errors appear in a bar under the toolbar. The window only displays
 * data and forwards the user's actions to the callbacks {@link GuiController} registers.
 */
public final class MainWindow extends BorderPane {

    static final String TITLE = "Find Anything";
    static final String READ_ONLY_STATUS =
            "Read-only: another Find Anything process is managing the index.";
    static final String NO_FOLDERS = "No search folders yet. Click “Add folder”.";
    static final String SEARCH_PROMPT = "Search your files";
    static final String ASK_PROMPT = "Ask a question about your files, then press Enter";
    static final String NO_CONVERSATIONS = "No saved conversations yet";

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
    private final FlowPane folderChips = new FlowPane(6, 6);
    private final HBox errorBar = new HBox(8);
    private final Label errorLabel = new Label();
    private final Label statusLabel = new Label();

    private SearchMode mode = SearchMode.SEARCH;
    private boolean readOnly;
    private List<SearchFolder> folders = List.of();
    private Consumer<String> onQueryChanged = query -> {};
    private Consumer<String> onAskSubmitted = request -> {};
    private Runnable onNewConversation = () -> {};
    private Consumer<String> onOpenConversation = id -> {};
    private Runnable onConversationsRequested = () -> {};
    private Consumer<Path> onAddFolder = folder -> {};
    private Consumer<Path> onRemoveFolder = folder -> {};

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
        addFolder.setOnAction(event -> chooseFolder());

        HBox toolbar =
                new HBox(
                        8,
                        searchToggle,
                        askToggle,
                        input,
                        newConversation,
                        conversations,
                        addFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        folderChips.getStyleClass().add("folder-chips");
        folderChips.setPadding(new Insets(0, 8, 8, 8));
        folderChips.setAlignment(Pos.CENTER_LEFT);

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

        setTop(new VBox(toolbar, folderChips, errorBar));

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
            return;
        }
        for (SearchFolder folder : folders) {
            folderChips.getChildren().add(chip(folder));
        }
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
        MenuButton chip = new MenuButton(name + suffix);
        chip.getStyleClass().add("chip");
        if (!folder.isAvailable()) {
            chip.getStyleClass().add("unavailable");
        }
        chip.setTooltip(new Tooltip(path.toString()));
        MenuItem remove = new MenuItem("Remove");
        remove.setDisable(readOnly);
        remove.setOnAction(event -> onRemoveFolder.accept(path));
        chip.getItems().add(remove);
        return chip;
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
        errorBar.setVisible(true);
        errorBar.setManaged(true);
    }

    private void hideError() {
        errorBar.setVisible(false);
        errorBar.setManaged(false);
    }

    public ResultsView getResultsView() {
        return resultsView;
    }

    public DetailPane getDetailPane() {
        return detailPane;
    }

    // ------------------------------------------------------------------ the user's actions

    /** Called with the field's text on every change in Search mode. */
    public void setOnQueryChanged(Consumer<String> onQueryChanged) {
        this.onQueryChanged = Objects.requireNonNull(onQueryChanged, "onQueryChanged");
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
}
