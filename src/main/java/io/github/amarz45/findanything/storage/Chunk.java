package io.github.amarz45.findanything.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * One consecutive piece of a file's extracted text. Chunks of a file are numbered from 0 in reading
 * order, and each keeps the page or slide it came from, so a citation can point to it.
 *
 * @param position the chunk's index within its file, starting at 0
 * @param pageNumber the 1-based page (PDF) or slide (PowerPoint) number, or 0 when the format has
 *     no pages (Word, text, Markdown)
 * @param text the chunk's text
 * @param embedding the chunk's vector, or an empty array until the embedding phase fills it in
 */
public record Chunk(int position, int pageNumber, String text, float[] embedding) {

    public Chunk {
        if (position < 0) {
            throw new IllegalArgumentException("position must be at least 0, was " + position);
        }
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must be at least 0, was " + pageNumber);
        }
        Objects.requireNonNull(text, "text");
        embedding = Objects.requireNonNull(embedding, "embedding").clone();
    }

    /** Returns a copy of the embedding, so callers can't change the chunk. */
    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Chunk that
                && position == that.position
                && pageNumber == that.pageNumber
                && text.equals(that.text)
                && Arrays.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, pageNumber, text, Arrays.hashCode(embedding));
    }

    @Override
    public String toString() {
        return "Chunk[position="
                + position
                + ", pageNumber="
                + pageNumber
                + ", text="
                + text
                + ", embedding="
                + embedding.length
                + " floats]";
    }
}
