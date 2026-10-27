package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the real forked parser on the test corpus; the facts come from testdata/FACTS.md. */
class IsolatedTextExtractorTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    static IsolatedTextExtractor extractor;

    @BeforeAll
    static void startExtractor() throws Exception {
        Path home = Path.of(System.getenv("FINDANY_HOME"), "extractor-test");
        extractor = new IsolatedTextExtractor(home, Duration.ofSeconds(120), 1024L * 1024 * 1024);
    }

    @AfterAll
    static void stopExtractor() throws Exception {
        extractor.close();
    }

    @Test
    void pdfPagesAreNumbered() throws Exception {
        ExtractedDocument lease = extractor.extract(CORPUS.resolve("housing/lease-2025-2026.pdf"));
        assertThat(lease.contentType()).isEqualTo("application/pdf");
        assertThat(lease.numberedPages()).isTrue();
        assertThat(lease.truncated()).isFalse();
        assertThat(lease.pages()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lease.pages().get(0)).contains("1,850");
        assertThat(lease.pages().get(1)).contains("August 31, 2026");
    }

    @Test
    void slidesComeInDeckOrderWithTheirNotes() throws Exception {
        ExtractedDocument lecture =
                extractor.extract(CORPUS.resolve("school/lecture-week-03-sorting.pptx"));
        assertThat(lecture.numberedPages()).isTrue();
        assertThat(lecture.pages()).hasSize(12);
        assertThat(lecture.pages().get(9)).containsIgnoringCase("merge sort").contains("n log n");

        ExtractedDocument coop =
                extractor.extract(CORPUS.resolve("school/coop-info-session-fall-2025.pptx"));
        assertThat(coop.pages().get(3)).contains("November 3, 2025");
        assertThat(coop.pages().get(4)).contains("résumé");
    }

    @Test
    void formatsWithoutPagesGiveOneUnnumberedPage() throws Exception {
        ExtractedDocument docx =
                extractor.extract(CORPUS.resolve("school/assignment-2-report.docx"));
        assertThat(docx.numberedPages()).isFalse();
        assertThat(docx.pages()).hasSize(1);
        assertThat(docx.pages().get(0)).contains("Insertion sort");

        ExtractedDocument markdown =
                extractor.extract(CORPUS.resolve("work/team-meeting-2026-03-12.md"));
        assertThat(markdown.contentType()).isEqualTo("text/markdown");
        assertThat(markdown.pages().get(0)).contains("April 20, 2026");

        ExtractedDocument text = extractor.extract(CORPUS.resolve("work/project-budget-2026.txt"));
        assertThat(text.pages().get(0)).contains("48,500");
    }

    @Test
    void unsupportedFormatsAreReportedWithoutParsing() {
        assertThatThrownBy(() -> extractor.extract(CORPUS.resolve("edge/photo.png")))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("image/png");
        assertThatThrownBy(() -> extractor.extract(CORPUS.resolve("edge/data.csv")))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("text/csv");
    }

    @Test
    void unreadableFilesFailWithAReason() {
        assertThatThrownBy(() -> extractor.extract(CORPUS.resolve("edge/password-protected.pdf")))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("password");
        assertThatThrownBy(() -> extractor.extract(CORPUS.resolve("edge/empty.txt")))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    void filesWithNoTextLayerGiveEmptyPagesWithoutAnError() throws Exception {
        assertThat(extractor.extract(CORPUS.resolve("edge/scanned-receipt.pdf")).hasNoText())
                .isTrue();
        assertThat(extractor.extract(CORPUS.resolve("edge/truncated.pdf")).hasNoText()).isTrue();
    }

    @Test
    void missingFilesAreAnIoError() {
        assertThatThrownBy(() -> extractor.extract(CORPUS.resolve("edge/does-not-exist.pdf")))
                .isInstanceOf(java.io.IOException.class);
    }
}
