package io.github.amarz45.findanything.indexing;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The paths waiting to be indexed (F03). Thread-safe: {@link #offer} is called from the watcher and
 * UI threads and only records the latest change time for a path, so a burst of events for one file
 * becomes a single entry. {@link #takeReady} is called by the indexing worker and returns a path
 * once it has been unchanged for the quiet period. A folder in the queue means "rescan this
 * folder".
 */
public final class ChangeQueue {

    private final Map<Path, Instant> lastChangeTimes = new HashMap<>();
    private final Duration quietPeriod;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    /**
     * @param quietPeriod how long a path must stay unchanged before it is handed out
     */
    public ChangeQueue(Duration quietPeriod) {
        this.quietPeriod = Objects.requireNonNull(quietPeriod, "quietPeriod");
        if (quietPeriod.isNegative()) {
            throw new IllegalArgumentException("quietPeriod must not be negative");
        }
    }

    /** Records that the path changed now. Returns immediately. */
    public void offer(Path path) {
        Path key = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        lock.lock();
        try {
            lastChangeTimes.put(key, Instant.now());
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until some path has been unchanged for the quiet period and returns it (the one that
     * has waited longest), or returns null when nothing is ready before the timeout.
     */
    public Path takeReady(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        Instant deadline = Instant.now().plus(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                Instant now = Instant.now();
                Map.Entry<Path, Instant> oldest = null;
                for (Map.Entry<Path, Instant> entry : lastChangeTimes.entrySet()) {
                    if (oldest == null || entry.getValue().isBefore(oldest.getValue())) {
                        oldest = entry;
                    }
                }
                if (oldest != null) {
                    Instant readyAt = oldest.getValue().plus(quietPeriod);
                    if (!readyAt.isAfter(now)) {
                        lastChangeTimes.remove(oldest.getKey());
                        return oldest.getKey();
                    }
                    if (readyAt.isBefore(deadline)) {
                        changed.await(
                                Duration.between(now, readyAt).toNanos(), TimeUnit.NANOSECONDS);
                        continue;
                    }
                }
                if (!now.isBefore(deadline)) {
                    return null;
                }
                changed.await(Duration.between(now, deadline).toNanos(), TimeUnit.NANOSECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    /** How many paths are waiting, ready or not. */
    public int size() {
        lock.lock();
        try {
            return lastChangeTimes.size();
        } finally {
            lock.unlock();
        }
    }

    public Duration getQuietPeriod() {
        return quietPeriod;
    }
}
