package io.github.amarz45.findanything.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A file as recorded in the index: its attributes, the hash of its content, and how extraction
 * went. The same attributes are stored with every chunk of the file, so filters apply inside a
 * query.
 *
 * @param path the file's absolute, normalized path
 * @param fileType the file's type as the user filters on it: the lowercase extension without the
 *     dot ("pdf", "docx"), or an empty string when the name has none
 * @param sizeBytes the file's size when it was indexed
 * @param modifiedTime the file's modification time when it was indexed
 * @param contentHash the SHA-256 of the file's bytes, as lowercase hex, or an empty string when the
 *     file couldn't be read
 * @param extractionStatus what extraction produced
 */
public record IndexedFile(
        Path path,
        String fileType,
        long sizeBytes,
        Instant modifiedTime,
        String contentHash,
        ExtractionStatus extractionStatus) {

    public IndexedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(fileType, "fileType");
        Objects.requireNonNull(modifiedTime, "modifiedTime");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(extractionStatus, "extractionStatus");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be at least 0, was " + sizeBytes);
        }
    }

    /** The file type for a path: its lowercase extension without the dot, or "" when none. */
    public static String fileTypeOf(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
