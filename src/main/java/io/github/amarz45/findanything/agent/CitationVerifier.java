package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The deterministic check on the model's output (F09): every quote in an answer must appear in the
 * cited file's indexed text, on the cited page. Only differences in whitespace are forgiven; a
 * quote that is close but not exact stays unverified.
 */
public final class CitationVerifier {

    /** Whitespace as files have it, which includes the non-breaking spaces PDFs are full of. */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private final SearchIndex searchIndex;

    public CitationVerifier(SearchIndex searchIndex) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
    }

    /**
     * Checks every citation of an answer.
     *
     * @return the answer with each citation's status set
     * @throws IOException when the index can't be read
     */
    public Answer verify(Answer answer) throws IOException {
        List<Citation> checked = new ArrayList<>();
        for (Citation citation : answer.citations()) {
            checked.add(citation.withStatus(verifyCitation(citation)));
        }
        return answer.withCitations(checked);
    }

    /**
     * Checks one citation against the index.
     *
     * @return {@code SOURCE_CHANGED} when the file is gone or differs on disk from what was
     *     indexed; {@code VERIFIED} when the quote is on the cited page (anywhere in the file for a
     *     citation without a page); otherwise {@code UNVERIFIED}, which includes a file that isn't
     *     indexed, a page the file doesn't have, and an empty quote
     * @throws IOException when the index can't be read
     */
    public VerificationStatus verifyCitation(Citation citation) throws IOException {
        Optional<IndexedFile> indexed = searchIndex.getFile(citation.file());
        if (indexed.isEmpty()) {
            return VerificationStatus.UNVERIFIED;
        }
        if (hasChangedOnDisk(indexed.get())) {
            return VerificationStatus.SOURCE_CHANGED;
        }
        String quote = normalize(citation.quote());
        if (quote.isEmpty()) {
            return VerificationStatus.UNVERIFIED;
        }
        StringBuilder pageText = new StringBuilder();
        for (Chunk chunk : searchIndex.getChunks(citation.file())) {
            if (citation.pageNumber() == 0 || chunk.pageNumber() == citation.pageNumber()) {
                pageText.append(chunk.text()).append(' ');
            }
        }
        return normalize(pageText.toString()).contains(quote)
                ? VerificationStatus.VERIFIED
                : VerificationStatus.UNVERIFIED;
    }

    /**
     * The same test the indexer's rescan uses: the size or the modification time (to the
     * millisecond, as the index stores it) differs, or the file can't be read any more.
     */
    private static boolean hasChangedOnDisk(IndexedFile indexed) {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(
                            indexed.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return !attrs.isRegularFile()
                    || attrs.size() != indexed.sizeBytes()
                    || !attrs.lastModifiedTime()
                            .toInstant()
                            .truncatedTo(ChronoUnit.MILLIS)
                            .equals(indexed.modifiedTime().truncatedTo(ChronoUnit.MILLIS));
        } catch (IOException e) {
            return true;
        }
    }

    /** Turns every run of whitespace into one space and trims the ends. */
    private static String normalize(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}
