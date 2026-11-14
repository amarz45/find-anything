package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.AnswerFormatException;
import io.github.amarz45.findanything.agent.Citation;
import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.agent.ResponseParser;
import io.github.amarz45.findanything.config.AlreadyCoveredException;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexStatusListener;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.InvalidFiltersException;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

/**
 * The GUI's controller: it turns the user's actions into {@link FindAnythingService} calls and puts
 * the results in the views. It runs on the JavaFX thread and never waits on the facade: futures are
 * handled with {@code Platform.runLater()}, and search results from an outdated query are dropped.
 * As the index's {@link IndexStatusListener} (Observer), it is called on the indexing thread and
 * hands each status to the JavaFX thread the same way.
 */
public final class GuiController implements IndexStatusListener {

    static final String FILE_GONE = "This file no longer exists.";
    static final String REQUEST_FAILED = "The request could not be completed.";

    /** How many privacy log entries the dialog shows (SD08). */
    static final int PRIVACY_LOG_ENTRIES = 100;

    private final FindAnythingService findAnythingService;
    private final MainWindow mainWindow;
    private final ResultsView resultsView;
    private final DetailPane detailPane;
    private final SettingsDialog settingsDialog;

    /** The current conversation, started on the first request in Ask mode (F12). */
    private Conversation conversation;

    /** Reads saved replies again when a conversation is reopened. */
    private final ResponseParser responseParser = new ResponseParser();

    private SearchFilters searchFilters = SearchFilters.NONE;
    private long latestQueryId;
    private String latestQuery = "";

    /** The newest status not shown yet; one UI update is scheduled while it is set. */
    private final AtomicReference<IndexStatus> pendingStatus = new AtomicReference<>();

    private boolean statusShown;

    public GuiController(FindAnythingService findAnythingService, MainWindow mainWindow) {
        this.findAnythingService =
                Objects.requireNonNull(findAnythingService, "findAnythingService");
        this.mainWindow = Objects.requireNonNull(mainWindow, "mainWindow");
        this.resultsView = mainWindow.getResultsView();
        this.detailPane = mainWindow.getDetailPane();
        this.settingsDialog = mainWindow.getSettingsDialog();

        mainWindow.setOnQueryChanged(this::onQueryChanged);
        mainWindow.setOnFiltersChanged(this::onFiltersChanged);
        mainWindow.setOnAskSubmitted(this::onAskSubmitted);
        mainWindow.setOnNewConversation(this::onNewConversation);
        mainWindow.setOnOpenConversation(this::onOpenConversation);
        mainWindow.setOnConversationsRequested(this::refreshConversations);
        mainWindow.setOnAddFolder(this::onAddFolder);
        mainWindow.setOnRemoveFolder(this::onRemoveFolder);
        mainWindow.setOnSetLocalOnly(this::onSetLocalOnly);
        settingsDialog.setOnTestBackend(
                (backendType, modelName) -> {
                    try {
                        onTestBackend(
                                findAnythingService.resolveModelSettings(backendType, modelName));
                    } catch (IOException | RuntimeException e) {
                        settingsDialog.showTestResult(false, describe(e));
                    }
                });
        settingsDialog.setOnRedactionToggled(this::onRedactionToggled);
        settingsDialog.setOnShowPrivacyLog(this::onShowPrivacyLog);
        resultsView.setOnResultSelected(this::onResultSelected);
        detailPane.setOnCitationClicked(this::onCitationClicked);

        findAnythingService.addStatusListener(this);
        showFolders();
        refreshConversations();
        showSettings();
        // A read-only process is never told again, so show the status it has now.
        onStatusChanged(findAnythingService.getIndexStatus());
        onQueryChanged("");
    }

    // ------------------------------------------------------------------ search (F04, F06)

    /**
     * Runs an instant search with the current filters; only the newest query's results reach the
     * table. Filters that don't make sense are reported by the search and shown in the error bar.
     */
    public void onQueryChanged(String query) {
        latestQuery = Objects.requireNonNull(query, "query");
        long queryId = ++latestQueryId;
        boolean filtered = !searchFilters.isEmpty();
        try {
            findAnythingService
                    .search(query, searchFilters)
                    .whenComplete(
                            (results, failure) ->
                                    Platform.runLater(
                                            () -> {
                                                if (queryId != latestQueryId) {
                                                    return;
                                                }
                                                if (cause(failure)
                                                        instanceof InvalidFiltersException) {
                                                    mainWindow.showFilterError(describe(failure));
                                                } else if (failure != null) {
                                                    mainWindow.showError(describe(failure));
                                                } else {
                                                    resultsView.setFiltersActive(filtered);
                                                    resultsView.setResults(results);
                                                }
                                            }));
        } catch (RuntimeException e) {
            mainWindow.showError(describe(e));
        }
    }

    /**
     * Stores the new filters and runs the current search again with them (F04). An earlier message
     * about the filters is removed; if the new ones don't make sense either, the search says so.
     */
    public void onFiltersChanged(SearchFilters searchFilters) {
        this.searchFilters = Objects.requireNonNull(searchFilters, "searchFilters");
        mainWindow.clearFilterError();
        onQueryChanged(latestQuery);
    }

    /** Fills the table with an answer's files, which no filter narrowed. */
    private void showAnswerFiles(List<SearchResult> files) {
        resultsView.setFiltersActive(false);
        resultsView.setResults(files);
    }

    // ------------------------------------------------------------------ preview (F11)

    /** Shows the selected file's preview, with the current query highlighted. */
    public void onResultSelected(SearchResult result) {
        Objects.requireNonNull(result, "result");
        showPreview(result.file().path(), latestQuery.isBlank() ? null : latestQuery);
    }

    /** Shows the cited file's preview at the quote; the pane offers Back to the conversation. */
    public void onCitationClicked(Citation citation) {
        Objects.requireNonNull(citation, "citation");
        showPreview(citation.file(), citation.quote().isBlank() ? null : citation.quote());
    }

    private void showPreview(Path file, String highlight) {
        try {
            findAnythingService
                    .preview(file, highlight)
                    .whenComplete(
                            (preview, failure) ->
                                    Platform.runLater(
                                            () -> {
                                                if (failure == null) {
                                                    detailPane.showPreview(preview);
                                                } else if (cause(failure)
                                                        instanceof FileNotFoundException) {
                                                    mainWindow.showError(FILE_GONE);
                                                } else {
                                                    mainWindow.showError(describe(failure));
                                                }
                                            }));
        } catch (RuntimeException e) {
            mainWindow.showError(describe(e));
        }
    }

    // ------------------------------------------------------------------ ask (F07, F08, F09, F12)

    /**
     * Sends a request to the agent in the current conversation, starting one if there is none. The
     * input is disabled until the answer arrives; the agent's steps and then the answer with its
     * citations appear in the detail pane, and the files it drew on fill the table.
     */
    public void onAskSubmitted(String request) {
        if (Objects.requireNonNull(request, "request").isBlank()) {
            return;
        }
        if (conversation == null && !startConversation()) {
            return;
        }
        Conversation current = conversation;
        mainWindow.setMode(SearchMode.ASK);
        mainWindow.setAskBusy(true);
        detailPane.showRequest(request);
        try {
            findAnythingService
                    .ask(
                            request,
                            current,
                            step ->
                                    Platform.runLater(
                                            () -> {
                                                if (conversation == current) {
                                                    detailPane.showAgentStep(step);
                                                }
                                            }))
                    .whenComplete(
                            (answer, failure) ->
                                    Platform.runLater(() -> showAnswer(current, answer, failure)));
        } catch (RuntimeException e) {
            mainWindow.setAskBusy(false);
            mainWindow.showError(describe(e));
        }
    }

    private void showAnswer(Conversation current, Answer answer, Throwable failure) {
        mainWindow.setAskBusy(false);
        if (conversation != current) {
            return; // another conversation was started meanwhile
        }
        if (failure != null) {
            // The model's own failures come back as the facade's fallback answer; this is the rest,
            // such as the conversation file not being writable.
            detailPane.showAnswer(
                    new Answer(REQUEST_FAILED, List.of(), List.of(), List.of(describe(failure))));
            return;
        }
        detailPane.showAnswer(answer);
        if (mainWindow.getMode() == SearchMode.ASK) {
            showAnswerFiles(answer.sourceFiles());
        }
        refreshConversations(); // the conversation was saved with this answer
    }

    /**
     * Reopens a saved conversation: its requests and answers fill the detail pane, its last
     * answer's files the table, and the next request continues it. Verification statuses aren't
     * saved, so the citations carry no mark.
     */
    public void onOpenConversation(String id) {
        Objects.requireNonNull(id, "id");
        Conversation opened;
        try {
            opened = findAnythingService.openConversation(id);
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
            return;
        }
        conversation = opened;
        detailPane.clearConversation();
        String request = null;
        for (Message message : opened.getMessages()) {
            if (message.role() == Message.Role.USER) {
                request = message.content();
            } else if (message.role() == Message.Role.ASSISTANT
                    && message.toolCalls().isEmpty()
                    && request != null) {
                detailPane.showPastExchange(request, pastAnswer(message.content()));
                request = null;
            }
        }
        showAnswerFiles(opened.getLastResults());
        mainWindow.setMode(SearchMode.ASK);
    }

    /** A saved reply as an answer. Every saved reply passed the parser once, but to be safe. */
    private Answer pastAnswer(String reply) {
        try {
            return responseParser.parseAnswer(new LlmResponse(reply, List.of()));
        } catch (AnswerFormatException e) {
            return new Answer(reply, List.of(), List.of(), List.of());
        }
    }

    private void refreshConversations() {
        try {
            mainWindow.showConversations(findAnythingService.listConversations());
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
    }

    /** Starts a new conversation with the current model and empties the conversation view. */
    public void onNewConversation() {
        if (!startConversation()) {
            return;
        }
        detailPane.clearConversation();
        mainWindow.setMode(SearchMode.ASK);
        showAnswerFiles(List.of());
    }

    private boolean startConversation() {
        try {
            conversation = findAnythingService.newConversation();
            return true;
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
            return false;
        }
    }

    // ------------------------------------------------------------------ settings (F13, F14)

    /**
     * Tests the backend on the background thread; if it answers, it becomes the backend for new
     * conversations (SD08). The outcome goes to the settings dialog.
     */
    public void onTestBackend(ModelSettings modelSettings) {
        Objects.requireNonNull(modelSettings, "modelSettings");
        settingsDialog.showModelSettings(modelSettings);
        try {
            findAnythingService
                    .testBackend(modelSettings)
                    .whenComplete(
                            (answered, failure) ->
                                    Platform.runLater(
                                            () ->
                                                    showTestOutcome(
                                                            modelSettings, answered, failure)));
        } catch (RuntimeException e) {
            settingsDialog.showTestResult(false, describe(e));
        }
    }

    private void showTestOutcome(ModelSettings modelSettings, Boolean answered, Throwable failure) {
        if (failure != null) {
            settingsDialog.showTestResult(false, describe(failure));
            return;
        }
        if (!answered) {
            settingsDialog.showTestResult(false, "The backend was reached but answered nothing.");
            return;
        }
        try {
            findAnythingService.setBackend(modelSettings);
        } catch (IOException | RuntimeException e) {
            settingsDialog.showTestResult(false, describe(e));
            return;
        }
        settingsDialog.showTestResult(
                true,
                "Connected. New conversations use the "
                        + CliApp.Model.describe(modelSettings)
                        + ".");
    }

    /** Turns one redaction category on or off (F14); the toggles are shown again as saved. */
    public void onRedactionToggled(String category, boolean enabled) {
        Objects.requireNonNull(category, "category");
        try {
            findAnythingService.setRedaction(category, enabled);
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
        settingsDialog.showRedaction(findAnythingService.getRedactionCategories());
    }

    /** Loads the most recent privacy log entries into the dialog (UC09). */
    public void onShowPrivacyLog() {
        try {
            settingsDialog.showPrivacyLog(findAnythingService.getPrivacyLog(PRIVACY_LOG_ENTRIES));
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
    }

    private void showSettings() {
        try {
            settingsDialog.showModelSettings(findAnythingService.getModelSettings());
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
        settingsDialog.showRedaction(findAnythingService.getRedactionCategories());
    }

    // ------------------------------------------------------------------ folders (F01)

    /** Adds a search folder; it is indexed in the background and its chip appears at once. */
    public void onAddFolder(Path folder) {
        Objects.requireNonNull(folder, "folder");
        try {
            findAnythingService.addSearchFolder(folder);
        } catch (IOException | AlreadyCoveredException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
        showFolders();
    }

    /** Removes a search folder; its files leave the index in the background. */
    public void onRemoveFolder(Path folder) {
        Objects.requireNonNull(folder, "folder");
        try {
            findAnythingService.removeSearchFolder(folder);
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
        showFolders();
    }

    /**
     * Marks a search folder "local model only", or removes the mark (F14): from the next request
     * on, text from its files is withheld from every cloud model.
     */
    public void onSetLocalOnly(Path folder, boolean localOnly) {
        Objects.requireNonNull(folder, "folder");
        try {
            findAnythingService.setLocalOnly(folder, localOnly);
        } catch (IOException | RuntimeException e) {
            mainWindow.showError(describe(e));
        }
        showFolders();
    }

    private void showFolders() {
        mainWindow.showFolders(findAnythingService.getIndexConfig().getSearchFolders());
        mainWindow.showLocalOnlyFolders(findAnythingService.getLocalOnlyFolders());
    }

    // ------------------------------------------------------------------ index status (F03)

    /**
     * Called on the indexing thread after each piece of work. The newest status is kept and one
     * update is scheduled on the JavaFX thread, so a burst of updates collapses into one.
     */
    @Override
    public void onStatusChanged(IndexStatus status) {
        Objects.requireNonNull(status, "status");
        if (pendingStatus.getAndSet(status) == null) {
            Platform.runLater(this::showPendingStatus);
        }
    }

    private void showPendingStatus() {
        IndexStatus status = pendingStatus.getAndSet(null);
        if (status == null) {
            return;
        }
        mainWindow.showStatus(status);
        // The indexer only reports after it changed something, so once it is up to date the
        // current search is run again to show the change. The first status is the one at startup.
        if (statusShown && status.isUpToDate() && mainWindow.getMode() == SearchMode.SEARCH) {
            onQueryChanged(latestQuery);
        }
        statusShown = true;
    }

    // ------------------------------------------------------------------ helpers

    private static Throwable cause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static String describe(Throwable failure) {
        return CliApp.describe(cause(failure));
    }
}
