package io.github.amarz45.findanything.indexing;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.embedding.EmbeddingException;
import io.github.amarz45.findanything.embedding.EmbeddingModel;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexMode;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single writer of the index (F02, F03, F05). Only started in {@link IndexMode#WRITER} mode.
 * Its worker thread takes paths from the {@link ChangeQueue}: a path that no longer exists is
 * removed, a folder is rescanned, and a file is extracted, chunked and stored (phase 1). Whenever
 * no path is waiting, it embeds the chunks that have no vectors yet, a batch at a time (phase 2),
 * so a file is found by keywords first and by meaning a little later. It commits when the queue
 * empties, when enough files changed and after each embedded batch, then tells its {@link
 * IndexStatusListener}s (Observer; they are called on the worker thread). {@link #requestUpdate}
 * only offers a path to the queue, so it returns immediately and is safe from any thread.
 */
public final class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);

    /** Commit after this many changed files even while the queue is still busy. */
    static final int COMMIT_EVERY_FILES = 20;

    /** A file that can't be read (locked) is retried this many times, then marked failed. */
    static final int MAX_READ_RETRIES = 3;

    /**
     * Phase 2 commits and looks at the queue again once about this many chunks are embedded, so a
     * file that changed meanwhile doesn't wait long and the status shows the progress.
     */
    static final int EMBED_CHUNKS_PER_BATCH = 64;

    /** How many contents phase 2 asks the index for at a time. */
    static final int EMBED_CONTENTS_PER_BATCH = 16;

    /** Texts handed to the model in one call; a stop request is noticed between calls. */
    static final int EMBED_TEXTS_PER_CALL = 16;

    /** After this many contents in a row fail to embed, phase 2 is off until the next start. */
    static final int MAX_EMBEDDING_FAILURES = 3;

    /** How long the worker waits for a path before checking whether it was asked to stop. */
    private static final Duration POLL = Duration.ofMillis(200);

    private final IndexConfig indexConfig;
    private final ChangeQueue changeQueue;
    private final TextExtractor textExtractor;
    private final Chunker chunker;
    private final SearchIndex searchIndex;
    private final EmbeddingModel embeddingModel;
    private final List<IndexStatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rebuildRequested = new AtomicBoolean();
    private final AtomicLong requested = new AtomicLong(); // requests made so far
    private volatile long completedRequests; // requests the worker knows it has finished
    private final Map<Path, Integer> readRetries = new HashMap<>(); // worker thread only
    private final Set<String> unembeddedContent = new HashSet<>(); // worker only: failed this run
    private Thread workerThread;
    private volatile IndexerState state = IndexerState.OPENING;
    private volatile boolean stopRequested;
    private int uncommittedChanges; // worker thread only
    private boolean reportedPending; // worker thread only: the last status sent had work pending
    private int embeddingFailures; // worker thread only: contents that failed in a row
    private boolean embeddingOff; // worker thread only: too many failures, no more tries this run

    public Indexer(
            IndexConfig indexConfig,
            ChangeQueue changeQueue,
            TextExtractor textExtractor,
            Chunker chunker,
            SearchIndex searchIndex,
            EmbeddingModel embeddingModel) {
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        this.changeQueue = Objects.requireNonNull(changeQueue, "changeQueue");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    /** Starts the worker thread. The index must be open in WRITER mode. */
    public synchronized void start() {
        if (searchIndex.getMode() != IndexMode.WRITER) {
            throw new IllegalStateException("The indexer only runs in WRITER mode.");
        }
        if (workerThread != null) {
            throw new IllegalStateException("The indexer is already started.");
        }
        state = IndexerState.IDLE;
        workerThread = new Thread(this::runLoop, "findany-indexer");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /** Lets the worker finish its current task, commit, and stop. Blocks until it has. */
    public void stop() throws InterruptedException {
        Thread thread;
        synchronized (this) {
            thread = workerThread;
        }
        stopRequested = true;
        if (thread != null) {
            thread.join();
        }
    }

    /** Queues a file or folder for indexing (or removal, if it no longer exists). */
    public void requestUpdate(Path path) {
        changeQueue.offer(path);
        requested.incrementAndGet();
    }

    /** Drops every search folder's entries and indexes them again from scratch. */
    public void rebuild() {
        rebuildRequested.set(true);
        indexConfig.getSearchFolders().forEach(folder -> changeQueue.offer(folder.getPath()));
        requested.incrementAndGet();
    }

    /**
     * The current status. A request counts as pending until the worker has emptied the queue after
     * it, which closes the gap between a path leaving the queue and its work starting.
     */
    public IndexStatus getStatus() {
        int failed;
        try {
            failed = searchIndex.listFiles(ExtractionStatus.FAILED).size();
        } catch (IOException e) {
            log.error("Could not count failed files: {}", e.getMessage());
            failed = 0;
        }
        int withoutEmbeddings;
        try {
            withoutEmbeddings = searchIndex.countChunksWithoutEmbeddings();
        } catch (IOException e) {
            log.error("Could not count the chunks without embeddings: {}", e.getMessage());
            withoutEmbeddings = 0;
        }
        int pending = changeQueue.size();
        if (pending == 0 && completedRequests < requested.get()) {
            pending = 1; // taken from the queue, being handled right now
        }
        return new IndexStatus(state, pending, failed, withoutEmbeddings);
    }

    public void addStatusListener(IndexStatusListener listener) {
        statusListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStatusListener(IndexStatusListener listener) {
        statusListeners.remove(listener);
    }

    // ------------------------------------------------------------------ the worker thread

    private void runLoop() {
        try {
            boolean embedded = false; // the last pass embedded a batch, so there may be more
            while (!stopRequested) {
                long seenRequests = requested.get(); // read before taking, see getStatus()
                Path path = changeQueue.takeReady(embedded ? Duration.ZERO : POLL);
                if (rebuildRequested.getAndSet(false)) {
                    dropEverything(); // before rescanning the folders rebuild() just offered
                }
                if (path != null) {
                    handle(path);
                    if (changeQueue.size() == 0 || uncommittedChanges >= COMMIT_EVERY_FILES) {
                        commitIfNeeded();
                    }
                } else {
                    commitIfNeeded();
                }
                embedded = false;
                if (changeQueue.size() == 0) {
                    // The files asked for so far are indexed; embedding them is not a pending file.
                    completedRequests = Math.max(completedRequests, seenRequests);
                    embedded = embedNextBatch();
                }
                if (changeQueue.size() == 0 && !embedded) {
                    boolean wasIdle = state == IndexerState.IDLE;
                    state = IndexerState.IDLE;
                    // A request made while the worker was waiting counts as finished only on the
                    // next pass, so an idle pass also reports when the last status still had
                    // work pending; otherwise listeners would never hear "up to date".
                    boolean caughtUp = reportedPending && completedRequests >= requested.get();
                    if (path != null || !wasIdle || caughtUp) {
                        notifyStatus();
                    }
                } else if (path != null || embedded) {
                    notifyStatus();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state = IndexerState.STOPPING;
            notifyStatus();
            commitIfNeeded();
            state = IndexerState.STOPPED;
            notifyStatus();
        }
    }

    private void handle(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                state = IndexerState.INDEXING;
                searchIndex.remove(path);
                uncommittedChanges++;
                log.info("Removed {}", path);
            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                state = IndexerState.RECONCILING;
                rescanFolder(path);
            } else {
                state = IndexerState.INDEXING;
                indexFile(path);
            }
        } catch (IOException e) {
            log.error("Could not update the index for {}: {}", path, e.getMessage());
        }
    }

    private void dropEverything() {
        state = IndexerState.RECONCILING;
        for (SearchFolder folder : indexConfig.getSearchFolders()) {
            try {
                searchIndex.remove(folder.getPath());
                uncommittedChanges++;
            } catch (IOException e) {
                log.error(
                        "Could not clear {} from the index: {}", folder.getPath(), e.getMessage());
            }
        }
        commitIfNeeded();
        notifyStatus();
    }

    private void commitIfNeeded() {
        if (uncommittedChanges == 0) {
            return;
        }
        try {
            searchIndex.commit();
            uncommittedChanges = 0;
        } catch (IOException e) {
            log.error("Could not commit the index: {}", e.getMessage());
        }
    }

    /**
     * Compares a folder on disk with the index: files whose size or modification time differ are
     * queued, and entries for files that are gone or now excluded are removed. A folder that is no
     * longer a search folder loses all its entries.
     */
    private void rescanFolder(Path folder) throws IOException {
        Set<Path> seen = new HashSet<>();
        boolean isSearchFolder = indexConfig.findFolderContaining(folder).isPresent();
        if (isSearchFolder) {
            Files.walkFileTree(
                    folder,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(folder) && !indexConfig.isIncluded(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (!attrs.isRegularFile() || !indexConfig.isIncluded(file)) {
                                return FileVisitResult.CONTINUE; // links and excluded files
                            }
                            seen.add(file.toAbsolutePath().normalize());
                            Optional<IndexedFile> indexed = searchIndex.getFile(file);
                            boolean unchanged =
                                    indexed.isPresent()
                                            && indexed.get().sizeBytes() == attrs.size()
                                            && indexed.get()
                                                    .modifiedTime()
                                                    .equals(modifiedTime(attrs));
                            if (!unchanged) {
                                changeQueue.offer(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            log.warn(
                                    "Could not read {} while rescanning: {}", file, e.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
        SearchFilters under = new SearchFilters(Set.of(), null, null, -1, -1, folder);
        for (IndexedFile indexed : searchIndex.listFiles(under, Integer.MAX_VALUE)) {
            if (!seen.contains(indexed.path())) {
                searchIndex.remove(indexed.path());
                uncommittedChanges++;
                log.info("Removed {} (gone or excluded)", indexed.path());
            }
        }
        log.info("Rescanned {}: {} files queued", folder, changeQueue.size());
    }

    /** Phase 1: makes one file keyword-searchable, or records why it couldn't be. */
    private void indexFile(Path file) throws IOException {
        if (!indexConfig.isIncluded(file)) {
            searchIndex.remove(file);
            uncommittedChanges++;
            return;
        }
        BasicFileAttributes attrs =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) {
            searchIndex.remove(file); // a link or a special file: never indexed
            uncommittedChanges++;
            return;
        }
        String contentHash;
        List<Chunk> chunks = List.of();
        ExtractionStatus status;
        try {
            contentHash = sha256(file);
            ExtractedDocument document = textExtractor.extract(file);
            chunks = chunker.split(file, document);
            status =
                    document.truncated()
                            ? ExtractionStatus.PARTIAL
                            : chunks.isEmpty() ? ExtractionStatus.NO_TEXT : ExtractionStatus.OK;
        } catch (UnsupportedFormatException e) {
            contentHash = sha256(file);
            status = ExtractionStatus.METADATA_ONLY;
            log.info("{}: {}; indexed by name only", file, e.getMessage());
        } catch (ExtractionException e) {
            contentHash = sha256(file);
            status = ExtractionStatus.FAILED;
            log.warn("{}: extraction failed: {}", file, e.getMessage());
        } catch (IOException e) {
            int attempt = readRetries.merge(file, 1, Integer::sum);
            if (attempt <= MAX_READ_RETRIES) {
                log.info(
                        "{}: can't be read yet ({}); retrying after the quiet period",
                        file,
                        e.getMessage());
                changeQueue.offer(file);
                return;
            }
            readRetries.remove(file);
            contentHash = "";
            status = ExtractionStatus.FAILED;
            log.warn(
                    "{}: still can't be read after {} tries: {}",
                    file,
                    MAX_READ_RETRIES,
                    e.getMessage());
        }
        readRetries.remove(file);
        IndexedFile indexed =
                new IndexedFile(
                        file.toAbsolutePath().normalize(),
                        IndexedFile.fileTypeOf(file),
                        attrs.size(),
                        modifiedTime(attrs),
                        contentHash,
                        status);
        searchIndex.put(indexed, chunks);
        uncommittedChanges++;
        log.info("Indexed {} ({}, {} chunks)", file, status, chunks.size());
    }

    /**
     * Phase 2: embeds the next contents that have no vectors yet and commits them (F05). Called
     * only when no path is waiting, and it stops early when one arrives.
     *
     * @return true when it embedded something, so there may be more to embed
     */
    private boolean embedNextBatch() {
        if (embeddingOff) {
            return false;
        }
        List<String> contentHashes;
        try {
            // Contents that failed earlier in this run are still without vectors, so ask for
            // that many more and leave them out.
            contentHashes =
                    new ArrayList<>(
                            searchIndex.findContentWithoutEmbeddings(
                                    EMBED_CONTENTS_PER_BATCH + unembeddedContent.size()));
        } catch (IOException e) {
            log.error("Could not look for chunks without embeddings: {}", e.getMessage());
            return false;
        }
        contentHashes.removeAll(unembeddedContent);
        if (contentHashes.isEmpty()) {
            return false;
        }
        if (state != IndexerState.EMBEDDING) {
            state = IndexerState.EMBEDDING;
            notifyStatus();
        }
        int embeddedChunks = 0;
        for (String contentHash : contentHashes) {
            if (stopRequested
                    || embeddingOff
                    || changeQueue.size() > 0
                    || embeddedChunks >= EMBED_CHUNKS_PER_BATCH) {
                break;
            }
            embeddedChunks += embedContent(contentHash);
        }
        commitIfNeeded();
        return embeddedChunks > 0;
    }

    /**
     * Embeds one content's chunks and stores the vectors on every file with that content. A content
     * that fails is left for the next start, and its files are still found by keywords.
     *
     * @return the number of chunks embedded; 0 when the content failed or a stop was requested
     */
    private int embedContent(String contentHash) {
        try {
            List<Chunk> chunks = searchIndex.getChunksOfContent(contentHash);
            List<float[]> vectors = new ArrayList<>();
            for (int from = 0; from < chunks.size(); from += EMBED_TEXTS_PER_CALL) {
                if (stopRequested) {
                    return 0; // nothing is stored for a content until all of it is embedded
                }
                int to = Math.min(from + EMBED_TEXTS_PER_CALL, chunks.size());
                vectors.addAll(
                        embeddingModel.embed(
                                chunks.subList(from, to).stream().map(Chunk::text).toList()));
            }
            searchIndex.addEmbeddings(contentHash, vectors);
            uncommittedChanges++;
            embeddingFailures = 0;
            return chunks.size();
        } catch (EmbeddingException | IOException | IllegalArgumentException e) {
            unembeddedContent.add(contentHash);
            embeddingFailures++;
            if (embeddingFailures < MAX_EMBEDDING_FAILURES) {
                log.warn(
                        "Could not embed a file's text, so it is found by keywords only: {}",
                        e.getMessage());
            } else {
                embeddingOff = true;
                log.warn(
                        "Embedding failed {} times in a row and is off until the next start; files"
                                + " without embeddings are found by keywords only. The last"
                                + " failure: {}",
                        MAX_EMBEDDING_FAILURES,
                        e.getMessage());
            }
            return 0;
        }
    }

    private void notifyStatus() {
        IndexStatus status = getStatus();
        reportedPending = !status.isUpToDate();
        for (IndexStatusListener listener : statusListeners) {
            try {
                listener.onStatusChanged(status);
            } catch (RuntimeException e) {
                log.error("A status listener failed: {}", e.toString());
            }
        }
    }

    /**
     * The file's modification time at the precision the index stores (milliseconds). File systems
     * report nanoseconds; comparing those with the stored value would see every file as changed.
     */
    private static Instant modifiedTime(BasicFileAttributes attrs) {
        return attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
