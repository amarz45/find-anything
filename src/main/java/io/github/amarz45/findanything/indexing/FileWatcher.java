package io.github.amarz45.findanything.indexing;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the search folders and records every change in the {@link ChangeQueue} (F03). It only
 * records: the indexing worker takes each path once it has been quiet and decides what to do with
 * it. The directory-watcher library stays inside this class (Adapter); {@link #onChange} and {@link
 * #onOverflow} hold the decisions in the design's own types.
 *
 * <p>Each search folder has its own library watcher, because a watcher's folders can't change once
 * it is built, and search folders never overlap. Walking a folder and registering it runs on a
 * watcher thread, so {@link #watch} returns at once.
 */
public final class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

    private final IndexConfig indexConfig;
    private final ChangeQueue changeQueue;
    private final Map<Path, DirectoryWatcher> watchedFolders = new HashMap<>();
    private ExecutorService watcherThreads; // null unless started

    public FileWatcher(IndexConfig indexConfig, ChangeQueue changeQueue) {
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        this.changeQueue = Objects.requireNonNull(changeQueue, "changeQueue");
    }

    /** Starts watching every search folder that can be reached. */
    public synchronized void start() {
        if (watcherThreads != null) {
            throw new IllegalStateException("The file watcher is already started.");
        }
        watcherThreads =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "findany-watcher");
                            thread.setDaemon(true);
                            return thread;
                        });
        for (SearchFolder folder : indexConfig.getSearchFolders()) {
            if (folder.isAvailable()) {
                watch(folder.getPath());
            }
        }
    }

    /** Stops watching every folder. Changes made from now on wait for the next rescan. */
    public synchronized void stop() {
        if (watcherThreads == null) {
            return;
        }
        Map<Path, DirectoryWatcher> watchers = Map.copyOf(watchedFolders);
        watchedFolders.clear(); // first, so a watcher that ends now is known to have been stopped
        watchers.values().forEach(FileWatcher::closeQuietly);
        watcherThreads.shutdownNow();
        watcherThreads = null;
    }

    /**
     * Starts watching a search folder and everything under it, except what the exclusion rules
     * leave out. Once the folder is registered it is offered to the queue, so a change made just
     * before that is still picked up by a rescan. A folder that can't be watched is logged, and its
     * changes are then picked up at startup and on request.
     */
    public synchronized void watch(Path folder) {
        Path root = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        if (watcherThreads == null) {
            throw new IllegalStateException("The file watcher is not started.");
        }
        if (watchedFolders.containsKey(root)) {
            return;
        }
        DirectoryWatcher watcher;
        try {
            watcher =
                    DirectoryWatcher.builder()
                            .path(root)
                            .listener(new FolderListener(root))
                            // Hashing would read every file at startup and follow links.
                            .fileHashing(false)
                            .fileTreeVisitor(new IncludedTreeVisitor(root))
                            .build();
        } catch (IOException e) {
            logNotWatched(root, e);
            return;
        }
        watchedFolders.put(root, watcher);
        ExecutorService threads = watcherThreads;
        threads.execute(
                () -> {
                    // Registers the folder on this thread, then reads events on another.
                    CompletableFuture<Void> watching = watcher.watchAsync(threads);
                    if (!watching.isCompletedExceptionally()) {
                        changeQueue.offer(root);
                    }
                    watching.whenComplete((nothing, failure) -> ended(root, watcher, failure));
                });
    }

    /** Stops watching a folder; does nothing when it isn't watched. */
    public synchronized void unwatch(Path folder) {
        Path root = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        DirectoryWatcher watcher = watchedFolders.remove(root);
        if (watcher != null) {
            closeQuietly(watcher);
        }
    }

    /**
     * Records one change. A path the rules leave out is ignored. So is a folder that was created or
     * changed: the files in it arrive as changes of their own. A deleted folder is recorded once,
     * and the worker removes everything under it.
     */
    void onChange(Path path, boolean directory, boolean deleted) {
        if (directory && !deleted) {
            return;
        }
        if (indexConfig.isIncluded(path)) {
            changeQueue.offer(path);
        }
    }

    /** The operating system lost events for this search folder, so all of it is rescanned. */
    void onOverflow(Path folder) {
        log.info("Some changes under {} were missed; rescanning the folder.", folder);
        changeQueue.offer(folder);
    }

    /** A watcher ended. Nothing is said when {@link #unwatch} or {@link #stop} ended it. */
    private synchronized void ended(Path folder, DirectoryWatcher watcher, Throwable failure) {
        if (!watchedFolders.remove(folder, watcher)) {
            return;
        }
        closeQuietly(watcher);
        if (failure != null) {
            logNotWatched(folder, failure);
        } else {
            log.warn(
                    "Stopped watching {}: it is gone or can no longer be watched. Changes there are"
                            + " picked up at startup and on request.",
                    folder);
        }
    }

    private static void logNotWatched(Path folder, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        log.warn(
                "Could not watch {}: {}. Changes there are picked up at startup and on request.",
                folder,
                cause.toString());
    }

    private static void closeQuietly(DirectoryWatcher watcher) {
        try {
            watcher.close();
        } catch (IOException e) {
            log.warn("Could not close a file watcher: {}", e.getMessage());
        }
    }

    /** Hands one search folder's events to {@link #onChange} and {@link #onOverflow}. */
    private final class FolderListener implements DirectoryChangeListener {

        private final Path folder;

        FolderListener(Path folder) {
            this.folder = folder;
        }

        @Override
        public void onEvent(DirectoryChangeEvent event) {
            if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
                onOverflow(folder);
            } else {
                onChange(
                        event.path(),
                        event.isDirectory(),
                        event.eventType() == DirectoryChangeEvent.EventType.DELETE);
            }
        }

        @Override
        public void onException(Exception e) {
            log.warn("A change under {} could not be recorded: {}", folder, e.toString());
        }
    }

    /**
     * Walks a search folder the way the rescan does: links are not followed, and a folder the rules
     * leave out is skipped with everything under it, so it is never registered.
     */
    private final class IncludedTreeVisitor implements FileTreeVisitor {

        private final Path folder;

        IncludedTreeVisitor(Path folder) {
            this.folder = folder;
        }

        @Override
        public void recursiveVisitFiles(Path start, Callback onDirectory, Callback onFile)
                throws IOException {
            Files.walkFileTree(
                    start,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) throws IOException {
                            if (!dir.equals(folder) && !indexConfig.isIncluded(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            onDirectory.call(dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            onFile.call(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            log.warn("Could not read {} while watching: {}", file, e.getMessage());
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
    }
}
