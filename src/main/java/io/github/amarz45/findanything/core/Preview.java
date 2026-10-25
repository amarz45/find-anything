package io.github.amarz45.findanything.core;

import io.github.amarz45.findanything.storage.IndexedFile;
import java.util.List;
import java.util.Objects;

/**
 * A file's extracted text with the ranges to highlight (F11). A file without text gives a
 * metadata-only preview: empty text and no highlights.
 *
 * @param file the file's record from the index
 * @param text the file's text, rebuilt from its chunks in order, with a "[page N]" line before each
 *     numbered page
 * @param highlights the ranges of {@code text} to highlight, in order; the view scrolls to the
 *     first
 */
public record Preview(IndexedFile file, String text, List<Range> highlights) {

    /** A half-open range {@code [start, end)} of the preview's text. */
    public record Range(int start, int end) {
        public Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("bad range " + start + ".." + end);
            }
        }
    }

    public Preview {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");
        highlights = List.copyOf(Objects.requireNonNull(highlights, "highlights"));
    }

    /** True when the file has no extracted text, so only its metadata can be shown. */
    public boolean isMetadataOnly() {
        return text.isEmpty();
    }
}
