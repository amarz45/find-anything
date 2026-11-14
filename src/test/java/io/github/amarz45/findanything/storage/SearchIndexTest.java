package io.github.amarz45.findanything.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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

    // ------------------------------------------------------------------ vectors (F05)

    static IndexedFile fileWithContent(String relative, String contentHash) {
        Path path = ROOT.resolve(relative);
        return new IndexedFile(
                path, IndexedFile.fileTypeOf(path), 100, JAN, contentHash, ExtractionStatus.OK);
    }

    static Chunk embedded(int position, String text, float... vector) {
        return new Chunk(position, position + 1, text, vector);
    }

    @Test
    void vectorSearchGivesTheNearestChunksFirstWithTheirSimilarity() throws Exception {
        index.put(
                file("a.txt", 10, JAN, ExtractionStatus.OK),
                List.of(embedded(0, "east", 1, 0, 0), embedded(1, "north", 0, 1, 0)));
        index.put(
                file("b.pdf", 10, MAR, ExtractionStatus.OK),
                List.of(embedded(0, "north-east", 0.6f, 0.8f, 0)));
        index.put(file("c.txt", 10, MAY, ExtractionStatus.OK), chunks("no vector yet"));
        index.put(file("edge/photo.png", 1_000, JAN, ExtractionStatus.METADATA_ONLY), List.of());
        index.commit();
        float[] east = {1, 0, 0};

        List<ChunkHit> hits = index.vectorSearch(east, SearchFilters.NONE, 10);
        assertThat(hits)
                .extracting(hit -> hit.chunk().text())
                .containsExactly("east", "north-east", "north");
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-3));
        assertThat(hits.get(1).score()).isCloseTo(0.6, within(1e-3));
        assertThat(hits.get(2).score()).isCloseTo(0.0, within(1e-3));
        assertThat(hits.get(1).file().path()).isEqualTo(ROOT.resolve("b.pdf"));
        assertThat(hits.get(1).chunk().pageNumber()).isEqualTo(1);

        assertThat(index.vectorSearch(east, SearchFilters.NONE, 1))
                .extracting(hit -> hit.chunk().text())
                .containsExactly("east");
        // The filter applies inside the query: the one hit asked for is the nearest PDF chunk,
        // not the nearest chunk overall.
        SearchFilters pdfs = new SearchFilters(Set.of("pdf"), null, null, -1, -1, null);
        assertThat(index.vectorSearch(east, pdfs, 1))
                .extracting(hit -> hit.chunk().text())
                .containsExactly("north-east");
    }

    @Test
    void anIndexWithoutVectorsGivesNoVectorHits() throws Exception {
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).isEmpty();
        assertThat(index.findContentWithoutEmbeddings(10)).isEmpty();
        assertThat(index.countChunksWithoutEmbeddings()).isZero();

        putAll();
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).isEmpty();
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(6); // files without text: none
    }

    @Test
    void addEmbeddingsGivesEveryFileWithThatContentItsVectors() throws Exception {
        Path report = ROOT.resolve("one/report.txt");
        Path copy = ROOT.resolve("two/copy-of-report.txt");
        index.put(fileWithContent("one/report.txt", "same"), chunks("first part", "second part"));
        index.put(
                fileWithContent("two/copy-of-report.txt", "same"),
                chunks("first part", "second part"));
        index.put(fileWithContent("notes.txt", "other"), chunks("a note"));
        index.put(file("edge/photo.png", 1_000, JAN, ExtractionStatus.METADATA_ONLY), List.of());
        index.commit();
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(5);
        assertThat(index.findContentWithoutEmbeddings(10)).containsExactly("same", "other");
        assertThat(index.findContentWithoutEmbeddings(1)).containsExactly("same");
        assertThat(index.getChunksOfContent("same"))
                .extracting(Chunk::text)
                .containsExactly("first part", "second part");

        index.addEmbeddings("same", List.of(new float[] {1, 0}, new float[] {0, 1}));
        index.commit();
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(1);
        assertThat(index.findContentWithoutEmbeddings(10)).containsExactly("other");
        assertThat(index.getChunksOfContent("same")).isEmpty();
        List<ChunkHit> nearest = index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 2);
        assertThat(nearest).extracting(hit -> hit.chunk().text()).containsOnly("first part");
        assertThat(nearest)
                .extracting(hit -> hit.file().path())
                .containsExactlyInAnyOrder(report, copy);

        // Everything else about the files is as it was.
        assertThat(index.countFiles()).isEqualTo(4);
        assertThat(index.keywordSearch("second", SearchFilters.NONE, 10)).hasSize(2);
        assertThat(index.getChunks(copy))
                .extracting(Chunk::text)
                .containsExactly("first part", "second part");
        assertThat(index.getChunks(copy)).extracting(Chunk::pageNumber).containsExactly(1, 2);
        assertThat(index.getFile(copy).orElseThrow().contentHash()).isEqualTo("same");
    }

    @Test
    void aFileThatIsStoredAgainNeedsItsVectorsAgain() throws Exception {
        index.put(fileWithContent("notes.txt", "old"), List.of(embedded(0, "a note", 1, 0)));
        index.commit();
        assertThat(index.countChunksWithoutEmbeddings()).isZero();
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).hasSize(1);

        index.put(fileWithContent("notes.txt", "new"), chunks("a changed note"));
        index.commit();
        assertThat(index.findContentWithoutEmbeddings(10)).containsExactly("new");
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).isEmpty();
    }

    @Test
    void aFileWhoseNewEntriesCannotBeStoredKeepsItsOldOnes() throws Exception {
        index.put(fileWithContent("notes.txt", "old"), List.of(embedded(0, "a note", 1, 0)));
        index.commit();
        // The index's vectors have two numbers; one with three can't be stored.
        assertThatThrownBy(
                        () ->
                                index.put(
                                        fileWithContent("notes.txt", "new"),
                                        List.of(embedded(0, "a changed note", 1, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        index.commit();
        assertThat(index.getChunks(ROOT.resolve("notes.txt")))
                .extracting(Chunk::text)
                .containsExactly("a note");
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).hasSize(1);
    }

    @Test
    void filesWithTheSameHashButOtherChunksAreEmbeddedOneAfterTheOther() throws Exception {
        // The same bytes, but one extraction was cut short.
        index.put(fileWithContent("full.pdf", "same"), chunks("page one", "page two"));
        index.put(fileWithContent("cut.pdf", "same"), chunks("page one"));
        index.commit();
        assertThat(index.getChunksOfContent("same")).hasSize(2);

        index.addEmbeddings("same", List.of(new float[] {1, 0}, new float[] {0, 1}));
        index.commit();
        assertThat(index.findContentWithoutEmbeddings(10)).containsExactly("same");
        assertThat(index.getChunksOfContent("same"))
                .extracting(Chunk::text)
                .containsExactly("page one");

        index.addEmbeddings("same", List.of(new float[] {1, 0}));
        index.commit();
        assertThat(index.findContentWithoutEmbeddings(10)).isEmpty();
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 10)).hasSize(3);
    }

    @Test
    void addEmbeddingsRefusesWhatItCannotStore() throws Exception {
        index.put(fileWithContent("notes.txt", "known"), chunks("one", "two"));
        index.commit();
        assertThatThrownBy(() -> index.addEmbeddings("known", List.of(new float[] {1, 0})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No indexed file with this content has 1 chunks.");
        assertThatThrownBy(() -> index.addEmbeddings("unknown", List.of(new float[] {1, 0})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> index.addEmbeddings("known", List.of(new float[] {1}, new float[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(2);

        try (SearchIndex reader = SearchIndex.open(folder.resolve("index"))) {
            assertThatThrownBy(
                            () ->
                                    reader.addEmbeddings(
                                            "known", List.of(new float[] {1}, new float[] {1})))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("read-only");
        }
    }

    // ------------------------------------------------------------------ name vectors (F05)

    @Test
    void aFileIsFoundByItsNameVectorAndKeepsItsChunkVectors() throws Exception {
        Path resume = ROOT.resolve("work/resume.pdf");
        Path photo = ROOT.resolve("edge/photo.png");
        Path notes = ROOT.resolve("notes.txt");
        index.put(
                file("work/resume.pdf", 10, JAN, ExtractionStatus.OK),
                List.of(embedded(0, "Education", 0, 1, 0), embedded(1, "Experience", 0, 0, 1)));
        index.put(file("edge/photo.png", 1_000, JAN, ExtractionStatus.METADATA_ONLY), List.of());
        index.put(file("notes.txt", 10, MAR, ExtractionStatus.OK), chunks("a note"));
        index.commit();
        assertThat(index.findFilesWithoutNameEmbeddings(10)).containsExactly(resume, photo, notes);
        assertThat(index.findFilesWithoutNameEmbeddings(2)).containsExactly(resume, photo);

        index.addNameEmbedding(resume, new float[] {1, 0, 0});
        index.addNameEmbedding(photo, new float[] {0.6f, 0.8f, 0});
        index.commit();
        assertThat(index.findFilesWithoutNameEmbeddings(10)).containsExactly(notes);

        // By name: a file comes with its first chunk, and a file without text with none.
        List<ChunkHit> byName = index.vectorSearch(new float[] {1, 0, 0}, SearchFilters.NONE, 2);
        assertThat(byName).extracting(hit -> hit.file().path()).containsExactly(resume, photo);
        assertThat(byName.get(0).chunk().text()).isEqualTo("Education");
        assertThat(byName.get(0).score()).isCloseTo(1.0, within(1e-3));
        assertThat(byName.get(1).chunk()).isNull();
        assertThat(byName.get(1).score()).isCloseTo(0.6, within(1e-3));

        // The chunks kept their own vectors when the name's was added.
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(1);
        assertThat(index.vectorSearch(new float[] {0, 0, 1}, SearchFilters.NONE, 1))
                .extracting(hit -> hit.chunk().text())
                .containsExactly("Experience");

        // An entry close by its name and by its text is one hit, with the higher score.
        List<ChunkHit> both =
                index.vectorSearch(new float[] {0.6f, 0.8f, 0}, SearchFilters.NONE, 10);
        assertThat(both).hasSize(3);
        assertThat(both.get(0).file().path()).isEqualTo(photo);
        assertThat(both.get(1).chunk().text()).isEqualTo("Education");
        assertThat(both.get(1).score()).isCloseTo(0.8, within(1e-3));

        // The filters apply to names too.
        SearchFilters pdfs = new SearchFilters(Set.of("pdf"), null, null, -1, -1, null);
        assertThat(index.vectorSearch(new float[] {0.6f, 0.8f, 0}, pdfs, 10))
                .extracting(hit -> hit.file().path())
                .containsOnly(resume);
    }

    @Test
    void theNameVectorOutlastsAddEmbeddingsButNotStoringTheFileAgain() throws Exception {
        Path notes = ROOT.resolve("notes.txt");
        index.put(fileWithContent("notes.txt", "old"), chunks("a note"));
        index.commit();
        index.addNameEmbedding(notes, new float[] {1, 0});
        index.commit();
        index.addEmbeddings("old", List.of(new float[] {0, 1}));
        index.commit();
        assertThat(index.findFilesWithoutNameEmbeddings(10)).isEmpty();
        assertThat(index.countChunksWithoutEmbeddings()).isZero();
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 1).get(0).score())
                .isCloseTo(1.0, within(1e-3));

        index.put(fileWithContent("notes.txt", "new"), chunks("a changed note"));
        index.commit();
        assertThat(index.findFilesWithoutNameEmbeddings(10)).containsExactly(notes);
        assertThat(index.vectorSearch(new float[] {1, 0}, SearchFilters.NONE, 1)).isEmpty();
    }

    @Test
    void addNameEmbeddingRefusesWhatItCannotStore() throws Exception {
        Path notes = ROOT.resolve("notes.txt");
        index.put(fileWithContent("notes.txt", "known"), chunks("a note"));
        index.commit();
        assertThatThrownBy(
                        () -> index.addNameEmbedding(ROOT.resolve("nope.txt"), new float[] {1, 0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't indexed");
        assertThatThrownBy(() -> index.addNameEmbedding(notes, new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");

        try (SearchIndex reader = SearchIndex.open(folder.resolve("index"))) {
            assertThatThrownBy(() -> reader.addNameEmbedding(notes, new float[] {1, 0}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("read-only");
        }
    }
}
