package io.github.amarz45.findanything.search;

import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.util.Objects;

/**
 * One file in a ranked result list, with its best-matching chunk. Each file appears once; two
 * copies of a file are two results.
 *
 * @param file the file
 * @param bestChunk the file's best-matching chunk, or null when the query was empty and the file is
 *     listed rather than matched
 * @param score the file's score; higher ranks first
 */
public record SearchResult(IndexedFile file, Chunk bestChunk, double score) {

    public SearchResult {
        Objects.requireNonNull(file, "file");
    }
}
