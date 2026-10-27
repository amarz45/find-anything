package io.github.amarz45.findanything.storage;

import java.util.Objects;

/**
 * One match returned by a search: the file, the chunk that matched, and the score. Higher scores
 * rank first.
 *
 * @param file the file
 * @param chunk the matching chunk, or null when the file matched by its name only because it has no
 *     extracted text (unsupported format, failed extraction, no text layer)
 * @param score the match's score
 */
public record ChunkHit(IndexedFile file, Chunk chunk, double score) {

    public ChunkHit {
        Objects.requireNonNull(file, "file");
    }
}
