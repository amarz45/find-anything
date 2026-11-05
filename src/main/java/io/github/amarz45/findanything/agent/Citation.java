package io.github.amarz45.findanything.agent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One source the model gave for a claim: a passage it quotes from a file. {@link CitationVerifier}
 * checks that the quote really is there (F09).
 *
 * @param file the cited file
 * @param pageNumber the 1-based page (PDF) or slide (PowerPoint) the quote is on, or 0 when the
 *     file has no pages (Word, text, Markdown) and the whole file is searched
 * @param quote the passage, word for word
 * @param status what verification found; {@link VerificationStatus#UNVERIFIED} until it is checked
 */
public record Citation(Path file, int pageNumber, String quote, VerificationStatus status) {

    public Citation {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must be at least 0, was " + pageNumber);
        }
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(status, "status");
    }

    /** A citation as the model gave it, not checked yet. */
    public Citation(Path file, int pageNumber, String quote) {
        this(file, pageNumber, quote, VerificationStatus.UNVERIFIED);
    }

    /** The same citation with the status verification found. */
    public Citation withStatus(VerificationStatus status) {
        return new Citation(file, pageNumber, quote, status);
    }
}
