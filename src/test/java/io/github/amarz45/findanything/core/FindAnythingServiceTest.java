package io.github.amarz45.findanything.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.AlreadyCoveredException;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.InvalidPatternException;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexerState;
import io.github.amarz45.findanything.indexing.IsolatedTextExtractor;
import io.github.amarz45.findanything.indexing.ScriptedExtractor;
import io.github.amarz45.findanything.indexing.TextExtractor;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexMode;
import io.github.amarz45.findanything.storage.InvalidFiltersException;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindAnythingServiceTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    @TempDir Path home;

    FindAnythingService service(Path appData, TextExtractor extractor) throws Exception {
        SettingsStore store = new SettingsStore(appData.resolve("settings.json"));
        IndexConfig config = store.loadIndexConfig();
        return new FindAnythingService(
                config,
                store,
                extractor,
                new Chunker(300),
                new ChangeQueue(Duration.ZERO),
                appData.resolve("index"),
                Executors.newFixedThreadPool(2));
    }

    /** Generous because CI's Windows runner starts the Tika child JVM and parses far slower. */
    static final Duration WAIT = Duration.ofSeconds(180);

    static void await(BooleanSupplier condition, Supplier<String> state)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting; " + state.get());
            }
            Thread.sleep(20);
        }
    }

    static void awaitUpToDate(FindAnythingService service) throws InterruptedException {
        await(
                () -> service.getIndexStatus().isUpToDate(),
                () -> "index status " + service.getIndexStatus());
    }

    @Test
    void indexesTheCorpusAndAnswersSearchesAndPreviews() throws Exception {
        Path appData = home.resolve("app");
        TextExtractor extractor =
                new IsolatedTextExtractor(
                        appData.resolve("tika"), Duration.ofSeconds(120), 1024L * 1024 * 1024);
        try (FindAnythingService service = service(appData, extractor)) {
            assertThat(service.getIndexStatus().state()).isEqualTo(IndexerState.OPENING);
            assertThat(service.open(false)).isEqualTo(IndexMode.WRITER);
            service.addSearchFolder(CORPUS);
            awaitUpToDate(service);

            List<SearchResult> budget =
                    service.search("approved budget", SearchFilters.NONE).join();
            assertThat(budget.get(0).file().path())
                    .isEqualTo(CORPUS.resolve("work/project-budget-2026.txt"));
            assertThat(budget.get(0).bestChunk().text()).contains("48,500");

            List<SearchResult> resume = service.search("resume", SearchFilters.NONE, 5).join();
            assertThat(resume)
                    .extracting(r -> r.file().path())
                    .contains(CORPUS.resolve("work/resume-jordan-rivera.docx"));

            Preview lease =
                    service.preview(
                                    CORPUS.resolve("housing/lease-2025-2026.pdf"),
                                    "August 31, 2026")
                            .join();
            assertThat(lease.text()).contains("[page 1]").contains("[page 2]");
            assertThat(lease.highlights()).isNotEmpty();
            Preview.Range first = lease.highlights().get(0);
            assertThat(lease.text().substring(first.start(), first.end()))
                    .isEqualTo("August 31, 2026");
            assertThat(lease.text().lastIndexOf("[page 2]")).isLessThan(first.start());

            Preview photo = service.preview(CORPUS.resolve("edge/photo.png"), null).join();
            assertThat(photo.isMetadataOnly()).isTrue();
            assertThat(photo.file().extractionStatus()).isEqualTo(ExtractionStatus.METADATA_ONLY);

            assertThat(service.listFailedFiles())
                    .extracting(f -> f.path().getFileName().toString())
                    .containsExactlyInAnyOrder("empty.txt", "password-protected.pdf");
            assertThat(service.search("SECRET id_ed25519", SearchFilters.NONE).join()).isEmpty();
            assertThat(service.countIndexedFiles())
                    .isEqualTo(23); // 25 files minus the two built-in exclusions
        }
    }

    @Test
    void managesFoldersExclusionsAndPausing() throws Exception {
        Path docs = home.resolve("docs");
        Files.createDirectories(docs.resolve("drafts"));
        Files.writeString(docs.resolve("plan.txt"), "The launch is on April 20.");
        Files.writeString(docs.resolve("drafts/old.txt"), "An old draft of the plan.");
        List<IndexStatus> seen = new CopyOnWriteArrayList<>();
        try (FindAnythingService service = service(home.resolve("app"), new ScriptedExtractor())) {
            service.addStatusListener(seen::add);
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);
            assertThatThrownBy(() -> service.addSearchFolder(docs.resolve("drafts")))
                    .isInstanceOf(AlreadyCoveredException.class);

            service.addExclusion("drafts", docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(1);
            assertThatThrownBy(() -> service.addExclusion("*", null))
                    .isInstanceOf(InvalidPatternException.class);
            assertThatThrownBy(() -> service.removeExclusion("nothing", null))
                    .isInstanceOf(IllegalArgumentException.class);
            service.removeExclusion("drafts", docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);

            service.setFolderPaused(docs, true);
            assertThat(service.search("plan", SearchFilters.NONE).join()).isEmpty();
            service.setFolderPaused(docs, false);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);

            SearchFilters backwards =
                    new SearchFilters(
                            Set.of(),
                            LocalDate.of(2026, 2, 1),
                            LocalDate.of(2026, 1, 1),
                            -1,
                            -1,
                            null);
            assertThatThrownBy(() -> service.search("plan", backwards).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(InvalidFiltersException.class);

            Files.delete(docs.resolve("drafts/old.txt"));
            assertThatThrownBy(() -> service.preview(docs.resolve("drafts/old.txt"), null).join())
                    .hasCauseInstanceOf(FileNotFoundException.class);
            awaitUpToDate(service);
            assertThat(service.search("draft", SearchFilters.NONE).join()).isEmpty();

            service.removeSearchFolder(docs);
            awaitUpToDate(service);
            assertThat(service.countIndexedFiles()).isZero();
            assertThat(seen).isNotEmpty();
        }
        // the settings were saved: the folder was removed again
        assertThat(
                        new SettingsStore(home.resolve("app/settings.json"))
                                .loadIndexConfig()
                                .getSearchFolders())
                .isEmpty();
    }

    @Test
    void aSecondProcessOpensReadOnlyAndRefusesChanges() throws Exception {
        Path docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("a.txt"), "alpha");
        Path appData = home.resolve("app");
        try (FindAnythingService writer = service(appData, new ScriptedExtractor());
                FindAnythingService reader = service(appData, new ScriptedExtractor())) {
            writer.open(false);
            assertThat(reader.open(false)).isEqualTo(IndexMode.READ_ONLY);
            assertThat(reader.getIndexStatus().state()).isEqualTo(IndexerState.READ_ONLY);
            assertThat(reader.getIndexStatus().isUpToDate()).isTrue();
            assertThatThrownBy(() -> reader.addSearchFolder(docs))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("read-only");
            assertThatThrownBy(reader::rebuildIndex).isInstanceOf(IllegalStateException.class);

            writer.addSearchFolder(docs);
            awaitUpToDate(writer);
            // the reader's own config doesn't know the folder; search results are filtered by it
            assertThat(reader.search("alpha", SearchFilters.NONE).join()).isEmpty();
            reader.getIndexConfig().addFolder(docs);
            await(
                    () -> !reader.search("alpha", SearchFilters.NONE).join().isEmpty(),
                    () -> "the reader has not seen the writer's commit"); // after a refresh
        }
    }
}
