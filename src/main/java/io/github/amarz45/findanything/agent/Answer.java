package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.search.SearchResult;
import java.util.List;
import java.util.Objects;

/**
 * What the agent returns for a request (F07, F08): the answer's text, the citations that back it,
 * the files it found or drew on, and anything the user should be told about how it was produced.
 *
 * @param text the answer, or the explanation of the files found
 * @param citations the quoted sources, each with its verification status
 * @param sourceFiles the files to show in the results table
 * @param notices messages for the user, such as "the model is unavailable" or "the step limit was
 *     reached"
 */
public record Answer(
        String text,
        List<Citation> citations,
        List<SearchResult> sourceFiles,
        List<String> notices) {

    public Answer {
        Objects.requireNonNull(text, "text");
        citations = List.copyOf(Objects.requireNonNull(citations, "citations"));
        sourceFiles = List.copyOf(Objects.requireNonNull(sourceFiles, "sourceFiles"));
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
    }

    /**
     * Whether the answer is backed by checked sources: it has at least one citation, and every
     * citation is {@link VerificationStatus#VERIFIED}. An answer with no citations, such as one
     * whose citations couldn't be parsed, is not verified.
     */
    public boolean isVerified() {
        return !citations.isEmpty()
                && citations.stream()
                        .allMatch(citation -> citation.status() == VerificationStatus.VERIFIED);
    }

    /** The same answer with other citations, as verification returns it. */
    public Answer withCitations(List<Citation> citations) {
        return new Answer(text, citations, sourceFiles, notices);
    }
}
