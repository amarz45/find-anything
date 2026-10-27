package io.github.amarz45.findanything.indexing;

import java.util.List;
import java.util.Objects;

/**
 * What a {@link TextExtractor} returns for one file: its detected type and its text, one string per
 * page (PDF) or slide (PowerPoint). A format without pages (Word, text, Markdown) gives a single
 * unnumbered page.
 *
 * @param contentType the detected MIME type, such as "application/pdf"
 * @param pages the text of each page in order; a page with no text is an empty string
 * @param numberedPages true when the entries of {@code pages} are real pages or slides, so a chunk
 *     from entry {@code i} is on page {@code i + 1}; false when the format has no pages and the
 *     single entry is the whole file
 * @param truncated true when extraction stopped at the size limit, so the pages are incomplete
 */
public record ExtractedDocument(
        String contentType, List<String> pages, boolean numberedPages, boolean truncated) {

    public ExtractedDocument {
        Objects.requireNonNull(contentType, "contentType");
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a document has at least one page");
        }
        if (!numberedPages && pages.size() != 1) {
            throw new IllegalArgumentException("a document without page numbers has one page");
        }
    }

    /** True when no page has any text (for example a scanned PDF with OCR off). */
    public boolean hasNoText() {
        return pages.stream().allMatch(String::isBlank);
    }
}
