package io.github.amarz45.findanything.core;

import io.github.amarz45.findanything.agent.AgentController;
import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.CitationVerifier;
import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.agent.ConversationStore;
import io.github.amarz45.findanything.agent.PromptBuilder;
import io.github.amarz45.findanything.agent.ReadExcerptTool;
import io.github.amarz45.findanything.agent.ResponseParser;
import io.github.amarz45.findanything.agent.SearchTool;
import io.github.amarz45.findanything.agent.ToolRegistry;
import io.github.amarz45.findanything.config.AlreadyCoveredException;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ExclusionRule;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.InvalidPatternException;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexStatusListener;
import io.github.amarz45.findanything.indexing.Indexer;
import io.github.amarz45.findanything.indexing.IndexerState;
import io.github.amarz45.findanything.indexing.TextExtractor;
import io.github.amarz45.findanything.llm.LlmClient;
import io.github.amarz45.findanything.llm.LlmClientFactory;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.search.SearchEngine;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexMode;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.InvalidFiltersException;
import io.github.amarz45.findanything.storage.SearchFilters;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only entry point for the GUI and the CLI (Facade). Methods that return a {@link
 * CompletableFuture} start their work on the background executor and return immediately; the GUI
 * never waits on a future, and the CLI simply calls {@code join()}. Other methods are quick and
 * synchronous. Folder and exclusion changes are rejected when the index is {@link
 * IndexMode#READ_ONLY}, because another process owns it (SM01).
 */
public final class FindAnythingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FindAnythingService.class);

    /** How often a read-only process picks up the writer's commits. */
    static final Duration READ_ONLY_REFRESH = Duration.ofSeconds(3);

    /** Results per search unless the caller asks for a different number. */
    public static final int DEFAULT_RESULT_LIMIT = 50;

    /** How many times the agent may call the model for one request. */
    static final int MAX_AGENT_STEPS = 8;

    /** The most file text one {@code read_excerpt} call returns: four full chunks. */
    static final int MAX_EXCERPT_CHARS = 6_000;

    /**
     * The most conversation history sent to a model, about 10,000 tokens, which leaves room for the
     * instructions and the reply in a local model's context.
     */
    static final int MAX_CONVERSATION_CHARS = 40_000;

    /** How many files the instant search lists when it stands in for an unavailable model. */
    static final int FALLBACK_RESULT_LIMIT = 10;

    /** The answer's text when instant search stands in for an unavailable model. */
    static final String FALLBACK_TEXT =
            "The model is unavailable, so there is no answer. These are the files that match the"
                    + " words of your request.";

    /** What the notice on such an answer starts with; the model's error follows. */
    public static final String MODEL_UNAVAILABLE = "The model is unavailable: ";

    private static final DateTimeFormatter CONVERSATION_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final IndexConfig indexConfig;
    private final SettingsStore settingsStore;
    private final LlmClientFactory llmClientFactory;
    private final ConversationStore conversationStore;
    private final TextExtractor textExtractor;
    private final Chunker chunker;
    private final ChangeQueue changeQueue;
    private final Path indexDirectory;
    private final ExecutorService backgroundExecutor;
    private final Clock clock;
    private final List<IndexStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    private SearchIndex searchIndex;
    private IndexMode indexMode;
    private Indexer indexer; // only in WRITER mode
    private SearchEngine searchEngine;
    private AgentController agentController;
    private ScheduledExecutorService refresher; // only in READ_ONLY mode
    private ModelSettings modelSettings; // loaded on first use; the model for new conversations

    public FindAnythingService(
            IndexConfig indexConfig,
            SettingsStore settingsStore,
            LlmClientFactory llmClientFactory,
            ConversationStore conversationStore,
            TextExtractor textExtractor,
            Chunker chunker,
            ChangeQueue changeQueue,
            Path indexDirectory,
            ExecutorService backgroundExecutor,
            Clock clock) {
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        this.llmClientFactory = Objects.requireNonNull(llmClientFactory, "llmClientFactory");
        this.conversationStore = Objects.requireNonNull(conversationStore, "conversationStore");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.changeQueue = Objects.requireNonNull(changeQueue, "changeQueue");
        this.indexDirectory = Objects.requireNonNull(indexDirectory, "indexDirectory");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Opens the index. If this process gets the write lock, it starts the indexing worker and
     * queues every search folder for a rescan (the startup catch-up). Otherwise the index is
     * read-only and is refreshed every few seconds. Calling this again returns the same mode.
     *
     * @param watchFiles whether to watch the search folders for live changes; live watching is not
     *     available yet, so changes are picked up at startup and on request (F03, modified)
     */
    public synchronized IndexMode open(boolean watchFiles) throws IOException {
        if (searchIndex != null) {
            return indexMode;
        }
        searchIndex = SearchIndex.open(indexDirectory);
        indexMode = searchIndex.getMode();
        searchEngine = new SearchEngine(searchIndex);
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new SearchTool(searchEngine));
        toolRegistry.register(new ReadExcerptTool(searchIndex, MAX_EXCERPT_CHARS));
        agentController =
                new AgentController(
                        toolRegistry,
                        new PromptBuilder(),
                        new ResponseParser(),
                        new CitationVerifier(searchIndex),
                        searchIndex,
                        MAX_AGENT_STEPS,
                        MAX_CONVERSATION_CHARS,
                        clock);
        if (indexMode == IndexMode.WRITER) {
            indexer = new Indexer(indexConfig, changeQueue, textExtractor, chunker, searchIndex);
            statusListeners.forEach(indexer::addStatusListener);
            indexer.start();
            for (SearchFolder folder : indexConfig.getSearchFolders()) {
                if (folder.isAvailable()) {
                    indexer.requestUpdate(folder.getPath());
                }
            }
            if (watchFiles) {
                log.info(
                        "Live file watching is not available in this version; changes are picked up"
                                + " at startup and on request.");
            }
        } else {
            refresher =
                    Executors.newSingleThreadScheduledExecutor(
                            runnable -> {
                                Thread thread = new Thread(runnable, "findany-refresh");
                                thread.setDaemon(true);
                                return thread;
                            });
            refresher.scheduleWithFixedDelay(
                    this::refreshQuietly,
                    READ_ONLY_REFRESH.toMillis(),
                    READ_ONLY_REFRESH.toMillis(),
                    TimeUnit.MILLISECONDS);
            IndexStatus status = getIndexStatus();
            statusListeners.forEach(listener -> listener.onStatusChanged(status));
        }
        return indexMode;
    }

    private void refreshQuietly() {
        try {
            searchIndex.refresh();
        } catch (IOException e) {
            log.warn("Could not refresh the read-only index: {}", e.getMessage());
        }
    }

    /** Stops the worker (finishing its current file and committing), then releases the index. */
    @Override
    public synchronized void close() throws IOException {
        if (indexer != null) {
            try {
                indexer.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            indexer = null;
        }
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
        backgroundExecutor.shutdown();
        try {
            if (searchIndex != null) {
                searchIndex.close();
                searchIndex = null;
            }
        } finally {
            textExtractor.close();
        }
    }

    public synchronized IndexMode getIndexMode() {
        return indexMode;
    }

    public IndexConfig getIndexConfig() {
        return indexConfig;
    }

    // ------------------------------------------------------------------ folders and exclusions
    // (F01)

    /** Adds a search folder, saves the settings, and queues it for indexing. */
    public synchronized SearchFolder addSearchFolder(Path folder)
            throws IOException, AlreadyCoveredException {
        requireWriter();
        SearchFolder searchFolder = indexConfig.addFolder(folder);
        settingsStore.saveIndexConfig(indexConfig);
        indexer.requestUpdate(searchFolder.getPath());
        return searchFolder;
    }

    /** Removes a search folder and queues the removal of its index entries. */
    public synchronized void removeSearchFolder(Path folder) throws IOException {
        requireWriter();
        SearchFolder searchFolder =
                indexConfig
                        .findFolder(folder)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                folder + " is not a search folder."));
        indexConfig.removeFolder(folder);
        settingsStore.saveIndexConfig(indexConfig);
        indexer.requestUpdate(searchFolder.getPath());
    }

    /** Pauses or resumes a folder: its entries are kept, but searches skip it while paused. */
    public synchronized void setFolderPaused(Path folder, boolean paused) throws IOException {
        requireWriter();
        SearchFolder searchFolder =
                indexConfig
                        .findFolder(folder)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                folder + " is not a search folder."));
        searchFolder.setPaused(paused);
        settingsStore.saveIndexConfig(indexConfig);
    }

    /**
     * Adds an exclusion pattern, globally ({@code folder} null) or to one search folder, and queues
     * a rescan so newly excluded files leave the index.
     */
    public synchronized void addExclusion(String pattern, Path folder)
            throws InvalidPatternException, IOException {
        requireWriter();
        ExclusionRule rule = ExclusionRule.parse(pattern);
        if (folder == null) {
            indexConfig.addGlobalRule(rule);
            settingsStore.saveIndexConfig(indexConfig);
            indexConfig.getSearchFolders().forEach(f -> indexer.requestUpdate(f.getPath()));
        } else {
            SearchFolder searchFolder = requireFolder(folder);
            searchFolder.addRule(rule);
            settingsStore.saveIndexConfig(indexConfig);
            indexer.requestUpdate(searchFolder.getPath());
        }
    }

    /**
     * Removes an exclusion pattern and queues a rescan so newly included files get indexed.
     *
     * @throws IllegalArgumentException when there is no such rule
     */
    public synchronized void removeExclusion(String pattern, Path folder)
            throws InvalidPatternException, IOException {
        requireWriter();
        ExclusionRule rule = ExclusionRule.parse(pattern);
        boolean removed;
        if (folder == null) {
            removed = indexConfig.removeGlobalRule(rule);
        } else {
            removed = requireFolder(folder).removeRule(rule);
        }
        if (!removed) {
            throw new IllegalArgumentException("There is no exclusion \"" + pattern + "\".");
        }
        settingsStore.saveIndexConfig(indexConfig);
        if (folder == null) {
            indexConfig.getSearchFolders().forEach(f -> indexer.requestUpdate(f.getPath()));
        } else {
            indexer.requestUpdate(folder);
        }
    }

    private SearchFolder requireFolder(Path folder) {
        return indexConfig
                .findFolder(folder)
                .orElseThrow(
                        () -> new IllegalArgumentException(folder + " is not a search folder."));
    }

    private void requireWriter() {
        requireOpen();
        if (indexMode != IndexMode.WRITER) {
            throw new IllegalStateException(
                    "Another Find Anything process is managing the index; this one is read-only.");
        }
    }

    private void requireOpen() {
        if (searchIndex == null) {
            throw new IllegalStateException("The index is not open; call open() first.");
        }
    }

    // ------------------------------------------------------------------ search and preview (F06,
    // F11)

    /** Searches with the default result limit. */
    public CompletableFuture<List<SearchResult>> search(String query, SearchFilters searchFilters) {
        return search(query, searchFilters, DEFAULT_RESULT_LIMIT);
    }

    /**
     * Searches on a background thread. The future fails with {@link InvalidFiltersException} when
     * the filters don't make sense. Results in paused or unavailable folders are left out.
     */
    public CompletableFuture<List<SearchResult>> search(
            String query, SearchFilters searchFilters, int limit) {
        requireOpen();
        List<String> errors = searchFilters.validate(indexConfig);
        if (!errors.isEmpty()) {
            return CompletableFuture.failedFuture(new InvalidFiltersException(errors));
        }
        return supply(() -> searchActiveFolders(query, searchFilters, limit));
    }

    /** Searches, leaving out the files in paused or unavailable folders. */
    private List<SearchResult> searchActiveFolders(
            String query, SearchFilters searchFilters, int limit) throws IOException {
        List<SearchFolder> active = indexConfig.getActiveFolders();
        return searchEngine.search(query, searchFilters, limit).stream()
                .filter(result -> active.stream().anyMatch(f -> f.contains(result.file().path())))
                .toList();
    }

    /**
     * Builds a preview of a file's text with the matches of {@code highlight} marked (SD06). The
     * future fails with {@link FileNotFoundException} when the file no longer exists (it is then
     * queued for removal from the index) and with {@link IllegalArgumentException} when the file
     * isn't indexed. A file without text gives a metadata-only preview.
     */
    public CompletableFuture<Preview> preview(Path file, String highlight) {
        requireOpen();
        return supply(
                () -> {
                    Path path = file.toAbsolutePath().normalize();
                    List<Chunk> chunks = searchIndex.getChunks(path);
                    Optional<IndexedFile> indexed = searchIndex.getFile(path);
                    if (!Files.exists(path)) {
                        if (indexer != null && indexed.isPresent()) {
                            indexer.requestUpdate(path);
                        }
                        throw new FileNotFoundException("This file no longer exists: " + path);
                    }
                    IndexedFile record =
                            indexed.orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "This file is not indexed: " + path));
                    if (chunks.isEmpty()) {
                        return new Preview(record, "", List.of());
                    }
                    String text = joinChunks(chunks);
                    return new Preview(record, text, findHighlights(text, highlight));
                });
    }

    /** Joins a file's chunks in order, with a "[page N]" line before each numbered page. */
    static String joinChunks(List<Chunk> chunks) {
        StringBuilder text = new StringBuilder();
        int page = 0;
        for (Chunk chunk : chunks) {
            if (chunk.pageNumber() != page) {
                page = chunk.pageNumber();
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append("[page ").append(page).append("]\n");
            } else if (text.length() > 0) {
                text.append('\n');
            }
            text.append(chunk.text());
        }
        return text.toString();
    }

    /**
     * Finds what to highlight: every occurrence of the whole phrase, ignoring case and runs of
     * whitespace; when the phrase never occurs, every occurrence of each of its words.
     */
    static List<Preview.Range> findHighlights(String text, String highlight) {
        if (highlight == null || highlight.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<Preview.Range> ranges = findAll(lower, highlight.strip().toLowerCase(Locale.ROOT));
        if (ranges.isEmpty()) {
            for (String word : highlight.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (word.length() >= 2) {
                    ranges.addAll(findAll(lower, word));
                }
            }
            ranges.sort((a, b) -> Integer.compare(a.start(), b.start()));
        }
        return ranges;
    }

    private static List<Preview.Range> findAll(String lowerText, String lowerNeedle) {
        List<Preview.Range> ranges = new ArrayList<>();
        String needle = lowerNeedle.replaceAll("\\s+", " ");
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                java.util.regex.Pattern.quote(needle).replace(" ", "\\E\\s+\\Q"))
                        .matcher(lowerText);
        while (matcher.find()) {
            ranges.add(new Preview.Range(matcher.start(), matcher.end()));
        }
        return ranges;
    }

    // ------------------------------------------------------------------ ask and conversations
    // (F07, F08, F12)

    /**
     * Answers a request in a conversation with the agent, on a background thread (SD03). The client
     * is always the one for the conversation's own model, whatever the current settings are. The
     * conversation is saved after the answer.
     *
     * <p>When the model can't be used (no network, an API error, the local runtime not running, no
     * key), the future still completes: with the instant search results for the request's words and
     * a notice that starts with {@link #MODEL_UNAVAILABLE} and gives the reason. The future fails
     * only when the index or the conversation's file can't be read or written.
     *
     * @param conversation the conversation the request belongs to; one request at a time
     * @param progressListener told each step of the agent, on the background thread
     */
    public CompletableFuture<Answer> ask(
            String request, Conversation conversation, Consumer<String> progressListener) {
        requireOpen();
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(progressListener, "progressListener");
        if (Objects.requireNonNull(request, "request").isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The request is empty."));
        }
        return supply(
                () -> {
                    Answer answer;
                    try {
                        LlmClient llmClient;
                        try {
                            llmClient =
                                    llmClientFactory.createClient(conversation.getModelSettings());
                        } catch (LlmException e) {
                            // The agent never ran, so the conversation doesn't hold the request.
                            conversation.addUserMessage(request);
                            throw e;
                        }
                        answer =
                                agentController.run(
                                        request, conversation, llmClient, progressListener);
                    } catch (LlmException e) {
                        log.warn("The model is unavailable: {}", e.getMessage());
                        answer = instantSearchInstead(request, conversation, e);
                    }
                    conversationStore.save(conversation);
                    return answer;
                });
    }

    /**
     * The answer when the model can't be used: the files instant search finds for the request, with
     * the reason as a notice. The conversation records the request and this outcome, so its history
     * stays a sequence of complete exchanges.
     */
    private Answer instantSearchInstead(
            String request, Conversation conversation, LlmException failure) throws IOException {
        List<SearchResult> results =
                searchActiveFolders(request, SearchFilters.NONE, FALLBACK_RESULT_LIMIT);
        conversation.addAssistantMessage(new LlmResponse(FALLBACK_TEXT, List.of()));
        conversation.setLastResults(results);
        return new Answer(
                FALLBACK_TEXT,
                List.of(),
                results,
                List.of(MODEL_UNAVAILABLE + failure.getMessage()));
    }

    /**
     * Starts a conversation with the current model, which it keeps for life. It is first saved when
     * its first request is answered.
     */
    public Conversation newConversation() throws IOException {
        String id =
                CONVERSATION_ID_TIME.format(LocalDateTime.now(clock))
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 4);
        return new Conversation(id, getModelSettings());
    }

    /** The saved conversations, the most recently used first. */
    public List<Conversation> listConversations() throws IOException {
        return conversationStore.listAll();
    }

    /**
     * Loads a saved conversation, with the model it was started with.
     *
     * @throws IOException when there is no such conversation or its file can't be read
     */
    public Conversation openConversation(String id) throws IOException {
        return conversationStore.load(id);
    }

    // ------------------------------------------------------------------ index status (F03)

    /** The index's status; in read-only mode, the state is {@link IndexerState#READ_ONLY}. */
    public IndexStatus getIndexStatus() {
        SearchIndex index;
        Indexer worker;
        synchronized (this) {
            index = searchIndex;
            worker = indexer;
        }
        if (index == null) {
            return new IndexStatus(IndexerState.OPENING, 0, 0, 0);
        }
        if (worker != null) {
            return worker.getStatus();
        }
        return new IndexStatus(IndexerState.READ_ONLY, 0, countFailed(index), 0);
    }

    private static int countFailed(SearchIndex index) {
        try {
            return index.listFiles(ExtractionStatus.FAILED).size();
        } catch (IOException e) {
            log.error("Could not count failed files: {}", e.getMessage());
            return 0;
        }
    }

    /** The indexed files whose extraction failed, for {@code findany index status --failed}. */
    public List<IndexedFile> listFailedFiles() throws IOException {
        requireOpen();
        return searchIndex.listFiles(ExtractionStatus.FAILED);
    }

    /** The number of indexed files. */
    public int countIndexedFiles() throws IOException {
        requireOpen();
        return searchIndex.countFiles();
    }

    /** Drops the index and builds it again from the search folders. */
    public synchronized void rebuildIndex() {
        requireWriter();
        indexer.rebuild();
    }

    /** Registers a status observer; it is called on the indexing worker thread. */
    public synchronized void addStatusListener(IndexStatusListener listener) {
        statusListeners.add(Objects.requireNonNull(listener, "listener"));
        if (indexer != null) {
            indexer.addStatusListener(listener);
        }
    }

    // ------------------------------------------------------------------ model backend (F13)

    /** The model new conversations use: the saved choice, or the environment's defaults. */
    public synchronized ModelSettings getModelSettings() throws IOException {
        if (modelSettings == null) {
            modelSettings = settingsStore.loadModelSettings();
        }
        return modelSettings;
    }

    /**
     * The settings to test for a backend the user picked: the current settings when they are for
     * the same backend, otherwise the environment's defaults for it, with the model name replaced
     * when one is given.
     */
    public synchronized ModelSettings resolveModelSettings(
            BackendType backendType, String modelName) throws IOException {
        ModelSettings current = getModelSettings();
        ModelSettings base =
                current.backendType() == backendType
                        ? current
                        : settingsStore.defaultModelSettings(backendType);
        if (modelName == null || modelName.isBlank()) {
            return base;
        }
        return new ModelSettings(backendType, modelName.strip(), base.baseUrl(), base.apiKey());
    }

    /**
     * Sends a minimal request to the backend on a background thread (UC08). The future completes
     * with true when it answered, false when it answered nothing, and fails with {@link
     * LlmException} when it can't be used; the message says what to fix.
     */
    public CompletableFuture<Boolean> testBackend(ModelSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return supply(() -> llmClientFactory.createClient(settings).testConnection());
    }

    /**
     * Saves the settings and uses them for new conversations. Existing conversations keep the model
     * they were created with. The API key is not saved; it stays in the environment.
     */
    public synchronized void setBackend(ModelSettings settings) throws IOException {
        settingsStore.saveModelSettings(Objects.requireNonNull(settings, "settings"));
        modelSettings = settings;
    }

    // ------------------------------------------------------------------ helpers

    private interface Task<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> supply(Task<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        backgroundExecutor.execute(
                () -> {
                    try {
                        future.complete(task.run());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
        return future;
    }
}
