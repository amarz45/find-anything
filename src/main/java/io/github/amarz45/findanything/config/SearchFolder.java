package io.github.amarz45.findanything.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One folder the user chose to search, with its own exclusion rules. Thread-safe: the indexing
 * thread reads it while the UI thread changes it.
 */
public final class SearchFolder {

    private final Path path;
    private volatile boolean paused;
    private volatile boolean available;
    private final CopyOnWriteArrayList<ExclusionRule> exclusionRules = new CopyOnWriteArrayList<>();

    /**
     * @param path the folder, made absolute and normalized
     * @param paused true when the folder's index entries are kept but searches skip it
     * @param available false when the folder can't be reached (deleted, or its drive is gone)
     * @param exclusionRules the folder's own rules
     */
    public SearchFolder(
            Path path, boolean paused, boolean available, List<ExclusionRule> exclusionRules) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.paused = paused;
        this.available = available;
        this.exclusionRules.addAll(Objects.requireNonNull(exclusionRules, "exclusionRules"));
    }

    /** Whether the file (or folder) is this folder or lies under it. */
    public boolean contains(Path file) {
        return file.toAbsolutePath().normalize().startsWith(path);
    }

    /** Whether one of this folder's own rules excludes the file. The file must be under it. */
    public boolean isExcluded(Path file) {
        Path relative = relativize(file);
        return exclusionRules.stream().anyMatch(rule -> rule.matches(relative));
    }

    /** The file's path relative to this folder. */
    public Path relativize(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(path)) {
            throw new IllegalArgumentException(normalized + " is not under " + path);
        }
        return path.relativize(normalized);
    }

    public void addRule(ExclusionRule rule) {
        exclusionRules.addIfAbsent(Objects.requireNonNull(rule, "rule"));
    }

    /** Removes a rule; returns false when the folder had no such rule. */
    public boolean removeRule(ExclusionRule rule) {
        return exclusionRules.remove(rule);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public Path getPath() {
        return path;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isAvailable() {
        return available;
    }

    /** A snapshot of the folder's rules. */
    public List<ExclusionRule> getExclusionRules() {
        return List.copyOf(exclusionRules);
    }

    @Override
    public String toString() {
        return path + (paused ? " (paused)" : "") + (available ? "" : " (unavailable)");
    }
}
