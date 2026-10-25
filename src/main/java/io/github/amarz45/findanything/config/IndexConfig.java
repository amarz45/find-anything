package io.github.amarz45.findanything.config;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which folders are indexed and which files inside them are left out (F01, F14). Thread-safe: the
 * indexing threads read it while the UI thread changes it.
 *
 * <p>Three kinds of rules apply, in this order: the built-in rules (credential locations, the app's
 * own data folder, and Office lock and temporary files), the user's global rules, and each search
 * folder's own rules.
 */
public final class IndexConfig {

    /**
     * Patterns that always apply. They keep SSH keys, GnuPG and AWS credentials, {@code .env}
     * files, private keys, browser profiles, version-control and dependency folders, and
     * Office/LibreOffice lock and temporary files out of the index.
     */
    static final List<String> BUILT_IN_PATTERNS =
            List.of(
                    ".ssh",
                    ".gnupg",
                    ".aws",
                    ".env",
                    ".env.*",
                    "*.pem",
                    "*.key",
                    "*.p12",
                    "*.pfx",
                    "id_rsa*",
                    "id_dsa*",
                    "id_ecdsa*",
                    "id_ed25519*",
                    ".mozilla",
                    "google-chrome",
                    "chromium",
                    "Chrome",
                    "BraveSoftware",
                    "Microsoft/Edge",
                    ".git",
                    "node_modules",
                    "~$*",
                    ".~lock.*#",
                    "*.tmp");

    private final Path appDataFolder;
    private final List<SearchFolder> searchFolders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ExclusionRule> globalRules = new CopyOnWriteArrayList<>();
    private final List<ExclusionRule> builtInRules;

    /**
     * An empty configuration. {@code appDataFolder} (the index, settings and logs) is never
     * indexed.
     */
    public IndexConfig(Path appDataFolder) {
        this(appDataFolder, List.of(), List.of());
    }

    /** A configuration loaded from settings; the folders are taken as they are, without checks. */
    public IndexConfig(
            Path appDataFolder, List<SearchFolder> searchFolders, List<ExclusionRule> globalRules) {
        this.appDataFolder =
                Objects.requireNonNull(appDataFolder, "appDataFolder").toAbsolutePath().normalize();
        this.searchFolders.addAll(Objects.requireNonNull(searchFolders, "searchFolders"));
        this.globalRules.addAll(Objects.requireNonNull(globalRules, "globalRules"));
        this.builtInRules = BUILT_IN_PATTERNS.stream().map(IndexConfig::builtIn).toList();
    }

    private static ExclusionRule builtIn(String pattern) {
        try {
            return ExclusionRule.parse(pattern);
        } catch (InvalidPatternException e) {
            throw new IllegalStateException("built-in pattern is invalid: " + pattern, e);
        }
    }

    /**
     * Adds a search folder.
     *
     * @throws NoSuchFileException when the folder doesn't exist
     * @throws NotDirectoryException when the path is a file
     * @throws AccessDeniedException when the folder can't be read
     * @throws AlreadyCoveredException when the folder is inside, or contains, a search folder
     */
    public synchronized SearchFolder addFolder(Path folder)
            throws IOException, AlreadyCoveredException {
        Path normalized = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new NoSuchFileException(normalized.toString(), null, "The folder doesn't exist.");
        }
        if (!Files.isDirectory(normalized)) {
            throw new NotDirectoryException(normalized.toString());
        }
        if (!Files.isReadable(normalized)) {
            throw new AccessDeniedException(
                    normalized.toString(), null, "The folder can't be read.");
        }
        for (SearchFolder existing : searchFolders) {
            if (existing.contains(normalized)) {
                throw new AlreadyCoveredException(
                        normalized + " is already covered by " + existing.getPath() + ".");
            }
            if (existing.getPath().startsWith(normalized)) {
                throw new AlreadyCoveredException(
                        normalized + " contains the search folder " + existing.getPath() + ".");
            }
        }
        SearchFolder searchFolder = new SearchFolder(normalized, false, true, List.of());
        searchFolders.add(searchFolder);
        return searchFolder;
    }

    /**
     * Removes a search folder.
     *
     * @throws IllegalArgumentException when the path isn't a search folder
     */
    public synchronized void removeFolder(Path folder) {
        SearchFolder searchFolder =
                findFolder(folder)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                folder + " is not a search folder."));
        searchFolders.remove(searchFolder);
    }

    /** The search folder with exactly this path, if there is one. */
    public Optional<SearchFolder> findFolder(Path folder) {
        Path normalized = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        return searchFolders.stream().filter(f -> f.getPath().equals(normalized)).findFirst();
    }

    /** The search folder that contains this file, if any. */
    public Optional<SearchFolder> findFolderContaining(Path file) {
        return searchFolders.stream().filter(f -> f.contains(file)).findFirst();
    }

    public synchronized void addGlobalRule(ExclusionRule rule) {
        globalRules.addIfAbsent(Objects.requireNonNull(rule, "rule"));
    }

    /** Removes a global rule; returns false when there was no such rule. */
    public synchronized boolean removeGlobalRule(ExclusionRule rule) {
        return globalRules.remove(rule);
    }

    /**
     * Whether a file belongs in the index: it lies in a search folder, outside the app's own data
     * folder, and no built-in, global or per-folder rule excludes it. Paused folders still include
     * their files, because pausing keeps index entries.
     */
    public boolean isIncluded(Path file) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (normalized.startsWith(appDataFolder)) {
            return false;
        }
        Optional<SearchFolder> folder = findFolderContaining(normalized);
        if (folder.isEmpty()) {
            return false;
        }
        Path relative = folder.get().relativize(normalized);
        for (ExclusionRule rule : builtInRules) {
            if (rule.matches(relative)) {
                return false;
            }
        }
        for (ExclusionRule rule : globalRules) {
            if (rule.matches(relative)) {
                return false;
            }
        }
        return !folder.get().isExcluded(normalized);
    }

    /** The folders searches cover: not paused and currently reachable. */
    public List<SearchFolder> getActiveFolders() {
        return searchFolders.stream().filter(f -> !f.isPaused() && f.isAvailable()).toList();
    }

    /** A snapshot of every search folder. */
    public List<SearchFolder> getSearchFolders() {
        return List.copyOf(searchFolders);
    }

    /** A snapshot of the user's global rules. */
    public List<ExclusionRule> getGlobalRules() {
        return List.copyOf(globalRules);
    }

    public List<ExclusionRule> getBuiltInRules() {
        return builtInRules;
    }

    public Path getAppDataFolder() {
        return appDataFolder;
    }
}
