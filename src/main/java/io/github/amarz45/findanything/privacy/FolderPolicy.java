package io.github.amarz45.findanything.privacy;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Which folders are "local model only" (F14): text from a file in such a folder is never sent to a
 * cloud model. A folder's mark covers everything under it.
 *
 * <p>Thread-safe: background request threads ask {@link #isLocalOnly} while the UI thread changes
 * the marks.
 */
public final class FolderPolicy {

    private final Set<Path> localOnlyFolders = new CopyOnWriteArraySet<>();

    /** A policy with no local-only folders. */
    public FolderPolicy() {}

    /** A policy loaded from the settings. */
    public FolderPolicy(Collection<Path> localOnlyFolders) {
        for (Path folder : Objects.requireNonNull(localOnlyFolders, "localOnlyFolders")) {
            setLocalOnly(folder, true);
        }
    }

    /** Marks a folder as local-only, or removes its mark. */
    public void setLocalOnly(Path folder, boolean localOnly) {
        Path normalized = normalize(folder);
        if (localOnly) {
            localOnlyFolders.add(normalized);
        } else {
            localOnlyFolders.remove(normalized);
        }
    }

    /** Whether the file is in a local-only folder, at any depth, or is such a folder itself. */
    public boolean isLocalOnly(Path file) {
        Path normalized = normalize(file);
        for (Path folder : localOnlyFolders) {
            if (normalized.startsWith(folder)) {
                return true;
            }
        }
        return false;
    }

    /** The marked folders, as a copy. */
    public Set<Path> getLocalOnlyFolders() {
        return Set.copyOf(localOnlyFolders);
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
