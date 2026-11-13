package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShownPassagesTest {

    static final Path ROOT = Path.of("data").toAbsolutePath();
    static final Path LEASE = ROOT.resolve("lease.pdf");
    static final Path DRAFT = ROOT.resolve("lease-draft.pdf");
    static final Path PRIVATE = ROOT.resolve("private");
    static final Path NOTES = PRIVATE.resolve("notes.md");
    static final Path PHOTO = PRIVATE.resolve("lease-signing.jpg");

    static final Chunk PARTIES = chunk(0, 1, "This lease is between the landlord and the tenant.");
    static final Chunk END = chunk(1, 2, "The lease ends on August 31, 2026.");
    static final Chunk NOTE = chunk(0, 0, "Remember to renew the lease in the spring.");

    static Chunk chunk(int position, int pageNumber, String text) {
        return new Chunk(position, pageNumber, text, new float[0]);
    }

    /** A search result; {@code bestChunk} is null for a file matched or listed without text. */
    static SearchResult result(Path path, Chunk bestChunk) {
        IndexedFile file =
                new IndexedFile(
                        path,
                        IndexedFile.fileTypeOf(path),
                        100,
                        Instant.ofEpochSecond(1),
                        "h",
                        ExtractionStatus.OK);
        return new SearchResult(file, bestChunk, 1);
    }

    @Test
    void givesTheQueryOfTheSearchThatGaveAPassage() {
        ShownPassages shownPassages = new ShownPassages(file -> false);
        assertThat(shownPassages.queryThatGave(LEASE, END)).isEmpty();

        shownPassages.record("lease ends", List.of(result(LEASE, END), result(NOTES, NOTE)));
        assertThat(shownPassages.queryThatGave(LEASE, END)).contains("lease ends");
        assertThat(shownPassages.queryThatGave(NOTES, NOTE)).contains("lease ends");
        // Another passage of the same file, and the same position in another file, weren't given.
        assertThat(shownPassages.queryThatGave(LEASE, PARTIES)).isEmpty();
        assertThat(shownPassages.queryThatGave(DRAFT, END)).isEmpty();
    }

    @Test
    void keepsTheFirstQueryThatGaveAPassage() {
        ShownPassages shownPassages = new ShownPassages(file -> false);
        shownPassages.record("lease ends", List.of(result(LEASE, END)));
        shownPassages.record("ends august", List.of(result(LEASE, END), result(DRAFT, END)));

        assertThat(shownPassages.queryThatGave(LEASE, END)).contains("lease ends");
        assertThat(shownPassages.queryThatGave(DRAFT, END)).contains("ends august");
    }

    @Test
    void aFileWithoutAPassageRecordsNothing() {
        ShownPassages shownPassages = new ShownPassages(file -> false);
        shownPassages.record("", List.of(result(LEASE, null)));

        assertThat(shownPassages.queryThatGave(LEASE, PARTIES)).isEmpty();
    }

    @Test
    void recordsNothingOfAResultThatHoldsAWithheldFile() {
        ShownPassages shownPassages = new ShownPassages(file -> file.startsWith(PRIVATE));
        // The whole result is withheld, even though the private file has no passage in it.
        shownPassages.record("lease ends", List.of(result(LEASE, END), result(PHOTO, null)));
        assertThat(shownPassages.queryThatGave(LEASE, END)).isEmpty();

        // The next result holds no withheld file, so the model is sent it.
        shownPassages.record("ends august", List.of(result(LEASE, END)));
        assertThat(shownPassages.queryThatGave(LEASE, END)).contains("ends august");
    }
}
