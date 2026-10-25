package io.github.amarz45.findanything.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchIndexTest {

    static final Path ROOT = Path.of("data").toAbsolutePath();
    static final Instant JAN = day(2026, 1, 15);
    static final Instant MAR = day(2026, 3, 15);
    static final Instant MAY = day(2026, 5, 15);

    @TempDir Path folder;
    SearchIndex index;

    @BeforeEach
    void open() throws Exception {
        index = SearchIndex.open(folder.resolve("index"));
    }

    @AfterEach
    void close() throws Exception {
        index.close();
    }

    static Instant day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .plusSeconds(43_200);
    }

    static IndexedFile file(String relative, long size, Instant modified, ExtractionStatus status) {
        Path path = ROOT.resolve(relative);
        return new IndexedFile(
                path, IndexedFile.fileTypeOf(path), size, modified, "hash-" + relative, status);
    }

    static List<Chunk> chunks(String... texts) {
        return java.util.stream.IntStream.range(0, texts.length)
                .mapToObj(i -> new Chunk(i, i + 1, texts[i], new float[0]))
                .toList();
    }

    void putAll() throws Exception {
        index.put(
                file("work/resume-jordan-rivera.docx", 20_000, MAR, ExtractionStatus.OK),
                chunks("Jordan Rivera\nRésumé", "Experience: wrote a résumé parser in Java."));
        index.put(
                file("work/project-budget-2026.txt", 500, MAY, ExtractionStatus.OK),
                chunks("Total approved budget is $48,500."));
        index.put(
                file("housing/lease-2025-2026.pdf", 90_000, JAN, ExtractionStatus.OK),
                chunks(
                        "Monthly rent is $1,850.",
                        "The lease ends on August 31, 2026.",
                        "No pets are permitted."));
        index.put(file("edge/photo.png", 1_000, JAN, ExtractionStatus.METADATA_ONLY), List.of());
        index.put(
                file("edge/password-protected.pdf", 2_000, MAR, ExtractionStatus.FAILED),
                List.of());
        index.commit();
    }

    @Test
    void opensAsWriterAndStartsEmpty() throws Exception {
        assertThat(index.getMode()).isEqualTo(IndexMode.WRITER);
        assertThat(index.countFiles()).isZero();
        assertThat(index.listFiles(SearchFilters.NONE, 10)).isEmpty();
        assertThat(index.getFile(ROOT.resolve("nope.txt"))).isEmpty();
        assertThat(index.getChunks(ROOT.resolve("nope.txt"))).isEmpty();
    }

    @Test
    void findsChunksByWordsWithAccentsFolded() throws Exception {
        putAll();
        List<ChunkHit> hits = index.keywordSearch("resume", SearchFilters.NONE, 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).file().path())
                .isEqualTo(ROOT.resolve("work/resume-jordan-rivera.docx"));
        assertThat(hits.get(0).score()).isPositive();
        assertThat(hits)
                .extracting(h -> h.chunk().text())
                .allSatisfy(t -> assertThat(t).containsIgnoringCase("résumé"));
        assertThat(index.keywordSearch("RÉSUMÉ", SearchFilters.NONE, 10)).hasSameSizeAs(hits);

        List<ChunkHit> lease =
                index.keywordSearch("when does the lease end", SearchFilters.NONE, 10);
        assertThat(lease.get(0).chunk().text()).contains("August 31, 2026");
        assertThat(lease.get(0).chunk().pageNumber()).isEqualTo(2);
    }

    @Test
    void findsFilesByNameEvenWithoutText() throws Exception {
        putAll();
        List<ChunkHit> hits = index.keywordSearch("photo", SearchFilters.NONE, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).file().extractionStatus()).isEqualTo(ExtractionStatus.METADATA_ONLY);
        assertThat(hits.get(0).chunk()).isNull();
        assertThat(index.getChunks(ROOT.resolve("edge/photo.png"))).isEmpty();
        assertThat(index.getFile(ROOT.resolve("edge/photo.png"))).isPresent();
        assertThat(index.listFiles(ExtractionStatus.FAILED))
                .extracting(f -> f.path().getFileName().toString())
                .containsExactly("password-protected.pdf");
    }

    @Test
    void specialCharactersAndBlankQueriesAreHarmless() throws Exception {
        putAll();
        assertThat(
                        index.keywordSearch(
                                "budget (approved) +$48,500 AND:OR \"", SearchFilters.NONE, 10))
                .isNotEmpty();
        assertThat(index.keywordSearch("   ", SearchFilters.NONE, 10)).isEmpty();
        assertThat(index.keywordSearch("+-&&||!(){}[]^\"~*?:\\/", SearchFilters.NONE, 10))
                .isEmpty();
    }

    @Test
    void putReplacesAFilesEntriesAndRemoveDropsFilesOrFolders() throws Exception {
        putAll();
        Path lease = ROOT.resolve("housing/lease-2025-2026.pdf");
        assertThat(index.getChunks(lease)).hasSize(3);
        index.put(
                file("housing/lease-2025-2026.pdf", 91_000, MAY, ExtractionStatus.PARTIAL),
                chunks("Only one chunk now."));
        index.commit();
        assertThat(index.getChunks(lease))
                .extracting(Chunk::text)
                .containsExactly("Only one chunk now.");
        assertThat(index.getFile(lease).orElseThrow().extractionStatus())
                .isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(index.keywordSearch("pets", SearchFilters.NONE, 10)).isEmpty();

        index.remove(lease);
        index.commit();
        assertThat(index.getFile(lease)).isEmpty();
        assertThat(index.countFiles()).isEqualTo(4);

        index.remove(ROOT.resolve("work"));
        index.commit();
        assertThat(index.countFiles()).isEqualTo(2);
        assertThat(index.keywordSearch("budget", SearchFilters.NONE, 10)).isEmpty();
        assertThat(index.getFile(ROOT.resolve("edge/photo.png"))).isPresent();
    }

    @Test
    void listsFilesNewestFirstAndAppliesFiltersInsideQueries() throws Exception {
        putAll();
        assertThat(index.listFiles(SearchFilters.NONE, 10))
                .extracting(f -> f.path().getFileName().toString())
                .containsExactly(
                        "project-budget-2026.txt",
                        "resume-jordan-rivera.docx",
                        "password-protected.pdf",
                        "lease-2025-2026.pdf",
                        "photo.png");
        assertThat(index.listFiles(SearchFilters.NONE, 2)).hasSize(2);

        SearchFilters pdfs = new SearchFilters(Set.of("pdf"), null, null, -1, -1, null);
        assertThat(index.listFiles(pdfs, 10)).extracting(IndexedFile::fileType).containsOnly("pdf");

        SearchFilters spring =
                new SearchFilters(
                        Set.of(),
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 31),
                        -1,
                        -1,
                        null);
        assertThat(index.listFiles(spring, 10))
                .extracting(IndexedFile::modifiedTime)
                .containsOnly(MAR);

        SearchFilters small = new SearchFilters(Set.of(), null, null, 0, 1_500, null);
        assertThat(index.listFiles(small, 10))
                .extracting(IndexedFile::sizeBytes)
                .containsExactlyInAnyOrder(500L, 1_000L);

        SearchFilters work = new SearchFilters(Set.of(), null, null, -1, -1, ROOT.resolve("work"));
        assertThat(index.listFiles(work, 10)).hasSize(2);
        assertThat(index.keywordSearch("lease", work, 10)).isEmpty();
        assertThat(index.keywordSearch("résumé", work, 10)).isNotEmpty();
        assertThat(
                        index.keywordSearch(
                                "résumé",
                                new SearchFilters(Set.of("pdf"), null, null, -1, -1, null),
                                10))
                .isEmpty();
    }

    @Test
    void aSecondOpenIsReadOnlyAndSeesEachCommitAfterRefresh() throws Exception {
        try (SearchIndex reader = SearchIndex.open(folder.resolve("index"))) {
            assertThat(reader.getMode()).isEqualTo(IndexMode.READ_ONLY);
            assertThatThrownBy(
                            () ->
                                    reader.put(
                                            file("x.txt", 1, JAN, ExtractionStatus.OK),
                                            chunks("x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("read-only");
            assertThat(reader.countFiles()).isZero();

            putAll();
            assertThat(reader.countFiles()).isZero(); // still the old snapshot
            reader.refresh();
            assertThat(reader.countFiles()).isEqualTo(5);
            assertThat(reader.keywordSearch("budget", SearchFilters.NONE, 5)).hasSize(1);
        }
        // closing the reader must not release the writer's lock
        try (SearchIndex another = SearchIndex.open(folder.resolve("index"))) {
            assertThat(another.getMode()).isEqualTo(IndexMode.READ_ONLY);
        }
    }

    @Test
    void theLockIsReleasedOnClose() throws Exception {
        index.close();
        try (SearchIndex reopened = SearchIndex.open(folder.resolve("index"))) {
            assertThat(reopened.getMode()).isEqualTo(IndexMode.WRITER);
        }
        index =
                SearchIndex.open(
                        folder.resolve(
                                "index")); // so the @AfterEach close() has something to close
    }
}
