package io.github.amarz45.findanything.storage;

import java.util.Objects;

/**
 * One chunk returned by a search, with the file it belongs to and its score. Higher scores rank
 * first.
 */
public record ChunkHit(IndexedFile file, Chunk chunk, double score) {

    public ChunkHit {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(chunk, "chunk");
    }
}
