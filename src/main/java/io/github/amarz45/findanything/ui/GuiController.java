package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.Citation;
import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.config.AlreadyCoveredException;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexStatusListener;
import io.github.amarz45.findanything.search.SearchResult;
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

    private final FindAnythingService findAnythingService;
    private final MainWindow mainWindow;
    private final ResultsView resultsView;
    private final DetailPane detailPane;

    /** The current conversation, started on the first request in Ask mode (F12). */
    private Conversation conversation;

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

        mainWindow.setOnQueryChanged(this::onQueryChanged);
        mainWindow.setOnAskSubmitted(this::onAskSubmitted);
        mainWindow.setOnNewConversation(this::onNewConversation);
        mainWindow.setOnAddFolder(this::onAddFolder);
        mainWindow.setOnRemoveFolder(this::onRemoveFolder);
        resultsView.setOnResultSelected(this::onResultSelected);
        detailPane.setOnCitationClicked(this::onCitationClicked);

        findAnythingService.addStatusListener(this);
        mainWindow.showFolders(findAnythingService.getIndexConfig().getSearchFolders());
        // A read-only process is never told again, so show the status it has now.
        onStatusChanged(findAnythingService.getIndexStatus());
        onQueryChanged("");
    }

    // ------------------------------------------------------------------ search (F06)

    /** Runs an instant search; only the newest query's results reach the table. */
    public void onQueryChanged(String query) {
        latestQuery = Objects.requireNonNull(query, "query");
        long queryId = ++latestQueryId;
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
                                                if (failure != null) {
                                                    mainWindow.showError(describe(failure));
                                                } else {
                                                    resultsView.setResults(results);
                                                }
                                            }));
        } catch (RuntimeException e) {
            mainWindow.showError(describe(e));
        }
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
            resultsView.setResults(answer.sourceFiles());
        }
    }

    /** Starts a new conversation with the current model and empties the conversation view. */
    public void onNewConversation() {
        if (!startConversation()) {
            return;
        }
        detailPane.clearConversation();
        mainWindow.setMode(SearchMode.ASK);
        resultsView.setResults(List.of());
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

    private void showFolders() {
        mainWindow.showFolders(findAnythingService.getIndexConfig().getSearchFolders());
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
