package io.github.amarz45.findanything.indexing;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
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
 * The single writer of the index (F02, F03). Only started in {@link IndexMode#WRITER} mode. Its
 * worker thread takes paths from the {@link ChangeQueue}: a path that no longer exists is removed,
 * a folder is rescanned, and a file is extracted, chunked and stored. It commits when the queue
 * empties or enough files changed, then tells its {@link IndexStatusListener}s (Observer; they are
 * called on the worker thread). {@link #requestUpdate} only offers a path to the queue, so it
 * returns immediately and is safe from any thread.
 */
public final class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);

    /** Commit after this many changed files even while the queue is still busy. */
    static final int COMMIT_EVERY_FILES = 20;

    /** A file that can't be read (locked) is retried this many times, then marked failed. */
    static final int MAX_READ_RETRIES = 3;

    /** How long the worker waits for a path before checking whether it was asked to stop. */
    private static final Duration POLL = Duration.ofMillis(200);

    private final IndexConfig indexConfig;
    private final ChangeQueue changeQueue;
    private final TextExtractor textExtractor;
    private final Chunker chunker;
    private final SearchIndex searchIndex;
    private final List<IndexStatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rebuildRequested = new AtomicBoolean();
    private final AtomicLong requested = new AtomicLong(); // requests made so far
    private volatile long completedRequests; // requests the worker knows it has finished
    private final Map<Path, Integer> readRetries = new HashMap<>(); // worker thread only
    private Thread workerThread;
    private volatile IndexerState state = IndexerState.OPENING;
    private volatile boolean stopRequested;
    private int uncommittedChanges; // worker thread only

    public Indexer(
            IndexConfig indexConfig,
            ChangeQueue changeQueue,
            TextExtractor textExtractor,
            Chunker chunker,
            SearchIndex searchIndex) {
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        this.changeQueue = Objects.requireNonNull(changeQueue, "changeQueue");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
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
        int pending = changeQueue.size();
        if (pending == 0 && completedRequests < requested.get()) {
            pending = 1; // taken from the queue, being handled right now
        }
        return new IndexStatus(state, pending, failed, 0);
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
            while (!stopRequested) {
                long seenRequests = requested.get(); // read before taking, see getStatus()
                Path path = changeQueue.takeReady(POLL);
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
                if (changeQueue.size() == 0 && !embedNextBatch()) {
                    completedRequests = Math.max(completedRequests, seenRequests);
                    boolean wasIdle = state == IndexerState.IDLE;
                    state = IndexerState.IDLE;
                    if (path != null || !wasIdle) {
                        notifyStatus();
                    }
                } else if (path != null) {
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
     * Phase 2: embeds chunks that have no vectors yet. There is no embedding model until F05, so
     * there is never anything to do.
     */
    private boolean embedNextBatch() {
        return false;
    }

    private void notifyStatus() {
        IndexStatus status = getStatus();
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
