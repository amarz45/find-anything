package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CitationVerifierTest {

    @TempDir Path indexFolder;
    @TempDir Path files;
    SearchIndex index;
    CitationVerifier verifier;
    Path lease;
    Path notes;

    @BeforeEach
    void setUp() throws Exception {
        index = SearchIndex.open(indexFolder);
        lease =
                indexFile(
                        "lease.pdf",
                        new Chunk(
                                0,
                                1,
                                "This lease is between the landlord\nand the tenant.",
                                none()),
                        new Chunk(1, 2, "The term begins on September 1, 2025.", none()),
                        new Chunk(2, 2, "The lease ends on August 31,   2026.", none()),
                        new Chunk(3, 3, "Rent is $1,850 per month.", none()));
        notes =
                indexFile(
                        "notes.md",
                        new Chunk(0, 0, "Renew the lease in the spring.", none()),
                        new Chunk(1, 0, "Call the landlord first.", none()));
        index.commit();
        verifier = new CitationVerifier(index);
    }

    @AfterEach
    void tearDown() throws Exception {
        index.close();
    }

    /** Writes a real file and indexes it with the size and time it has on disk. */
    Path indexFile(String name, Chunk... chunks) throws IOException {
        Path path = files.resolve(name);
        Files.writeString(path, "the original bytes of " + name);
        index.put(
                new IndexedFile(
                        path,
                        IndexedFile.fileTypeOf(path),
                        Files.size(path),
                        Files.getLastModifiedTime(path).toInstant(),
                        "h",
                        ExtractionStatus.OK),
                List.of(chunks));
        return path;
    }

    static float[] none() {
        return new float[0];
    }

    VerificationStatus check(Path file, int pageNumber, String quote) throws IOException {
        return verifier.verifyCitation(new Citation(file, pageNumber, quote));
    }

    @Test
    void verifiesAQuoteOnTheCitedPageIgnoringWhitespace() throws Exception {
        assertThat(check(lease, 2, "The lease ends on August 31, 2026."))
                .isEqualTo(VerificationStatus.VERIFIED);
        assertThat(check(lease, 1, "  between the landlord and\n\tthe tenant "))
                .isEqualTo(VerificationStatus.VERIFIED);
        // A quote that runs across two chunks of the same page.
        assertThat(check(lease, 2, "September 1, 2025. The lease ends"))
                .isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void doesNotVerifyAQuoteFromAnotherPageOrOneThatIsOnlyClose() throws Exception {
        assertThat(check(lease, 3, "The lease ends on August 31, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(check(lease, 9, "The lease ends on August 31, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(check(lease, 2, "The lease ends on August 30, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(check(lease, 2, "the lease ends on august 31, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(check(lease, 2, "The leaseends on August 31, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(check(lease, 2, "  \n ")).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void searchesTheWholeFileForACitationWithoutAPage() throws Exception {
        assertThat(check(notes, 0, "Call the landlord first."))
                .isEqualTo(VerificationStatus.VERIFIED);
        assertThat(check(notes, 0, "the spring. Call the landlord"))
                .isEqualTo(VerificationStatus.VERIFIED);
        assertThat(check(lease, 0, "Rent is $1,850 per month."))
                .isEqualTo(VerificationStatus.VERIFIED);
        // The file has no pages, so a page number is a claim the index can't back.
        assertThat(check(notes, 1, "Call the landlord first."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void doesNotVerifyACitationOfAFileThatIsNotIndexed() throws Exception {
        Path other = files.resolve("other.txt");
        Files.writeString(other, "The lease ends on August 31, 2026.");
        assertThat(check(other, 0, "The lease ends on August 31, 2026."))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void marksAFileThatChangedOrWentAwaySinceItWasIndexed() throws Exception {
        Files.writeString(lease, "different bytes, so a different size than before");
        assertThat(check(lease, 2, "The lease ends on August 31, 2026."))
                .isEqualTo(VerificationStatus.SOURCE_CHANGED);

        Files.delete(notes);
        assertThat(check(notes, 0, "Call the landlord first."))
                .isEqualTo(VerificationStatus.SOURCE_CHANGED);
    }

    @Test
    void verifySetsEveryCitationsStatusAndKeepsTheRestOfTheAnswer() throws Exception {
        SearchResult source = new SearchResult(index.getFile(lease).orElseThrow(), null, 1.0);
        Answer answer =
                new Answer(
                        "The lease ends on August 31, 2026 [1]. Rent is $1,900 [2].",
                        List.of(
                                new Citation(lease, 2, "The lease ends on August 31, 2026."),
                                new Citation(lease, 3, "Rent is $1,900 per month.")),
                        List.of(source),
                        List.of("One source was withheld."));
        assertThat(answer.isVerified()).isFalse();

        Answer verified = verifier.verify(answer);
        assertThat(verified.citations())
                .extracting(Citation::status)
                .containsExactly(VerificationStatus.VERIFIED, VerificationStatus.UNVERIFIED);
        assertThat(verified.isVerified()).isFalse();
        assertThat(verified.text()).isEqualTo(answer.text());
        assertThat(verified.sourceFiles()).containsExactly(source);
        assertThat(verified.notices()).containsExactly("One source was withheld.");

        Answer allGood = verifier.verify(answer.withCitations(answer.citations().subList(0, 1)));
        assertThat(allGood.isVerified()).isTrue();
    }

    @Test
    void anAnswerWithoutCitationsIsNotVerified() throws Exception {
        Answer answer = new Answer("I found nothing about that.", List.of(), List.of(), List.of());
        assertThat(verifier.verify(answer)).isEqualTo(answer);
        assertThat(answer.isVerified()).isFalse();
    }
}
