package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.ExclusionRule;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the worker with a scripted extractor, so the tests are fast and deterministic. */
class IndexerTest {

    @TempDir Path home;
    Path docs;
    IndexConfig config;
    ChangeQueue queue;
    SearchIndex index;
    Indexer indexer;
    io.github.amarz45.findanything.embedding.ScriptedEmbeddingModel model =
            new io.github.amarz45.findanything.embedding.ScriptedEmbeddingModel();
    List<IndexStatus> notifications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        docs = home.resolve("docs");
        Files.createDirectories(docs.resolve("sub"));
        Files.createDirectories(docs.resolve(".ssh"));
        Files.writeString(
                docs.resolve("lease.pdf"), "Rent is $1,850.\fThe lease ends on August 31, 2026.");
        Files.writeString(docs.resolve("sub/notes.txt"), "Dentist appointment on March 4.");
        Files.writeString(docs.resolve("photo.bin"), "not text");
        Files.writeString(docs.resolve("corrupt.txt"), "whatever");
        Files.writeString(docs.resolve(".ssh/id_ed25519"), "SECRET");
        Files.writeString(docs.resolve("scratch.tmp"), "temporary");
        config = new IndexConfig(home.resolve("app"));
        config.addFolder(docs);
        queue = new ChangeQueue(Duration.ZERO);
        index = SearchIndex.open(home.resolve("app/index"));
        indexer =
                new Indexer(config, queue, new ScriptedExtractor(), new Chunker(200), index, model);
        indexer.addStatusListener(notifications::add);
        indexer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        indexer.stop();
        index.close();
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting");
            }
            Thread.sleep(20);
        }
    }

    void rescanAndWait() throws InterruptedException {
        indexer.requestUpdate(docs);
        await(() -> indexer.getStatus().isUpToDate());
    }

    @Test
    void indexesAFolderWithEachFilesStatusAndSkipsExcludedFiles() throws Exception {
        rescanAndWait();
        assertThat(index.getFile(docs.resolve("lease.pdf")).orElseThrow().extractionStatus())
                .isEqualTo(ExtractionStatus.OK);
        assertThat(index.getChunks(docs.resolve("lease.pdf")))
                .extracting(Chunk::pageNumber)
                .containsExactly(1, 2);
        assertThat(index.getFile(docs.resolve("sub/notes.txt"))).isPresent();
        assertThat(index.getFile(docs.resolve("photo.bin")).orElseThrow().extractionStatus())
                .isEqualTo(ExtractionStatus.METADATA_ONLY);
        assertThat(index.getFile(docs.resolve("corrupt.txt")).orElseThrow().extractionStatus())
                .isEqualTo(ExtractionStatus.FAILED);
        assertThat(index.getFile(docs.resolve(".ssh/id_ed25519"))).isEmpty();
        assertThat(index.getFile(docs.resolve("scratch.tmp"))).isEmpty();
        assertThat(index.keywordSearch("SECRET", SearchFilters.NONE, 5)).isEmpty();
        assertThat(index.keywordSearch("dentist", SearchFilters.NONE, 5)).hasSize(1);

        IndexStatus status = indexer.getStatus();
        assertThat(status.state()).isEqualTo(IndexerState.IDLE);
        assertThat(status.failedFiles()).isEqualTo(1);
        assertThat(notifications).isNotEmpty();
        assertThat(notifications)
                .extracting(IndexStatus::state)
                .contains(IndexerState.RECONCILING, IndexerState.INDEXING, IndexerState.IDLE);
    }

    @Test
    void aRescanPicksUpEditsDeletionsAndNewExclusions() throws Exception {
        rescanAndWait();
        Path notes = docs.resolve("sub/notes.txt");
        Instant before = index.getFile(notes).orElseThrow().modifiedTime();
        Files.writeString(notes, "Dentist moved to March 11.");
        Files.setLastModifiedTime(notes, FileTime.from(before.plusSeconds(90)));
        Files.delete(docs.resolve("photo.bin"));
        config.getSearchFolders().get(0).addRule(ExclusionRule.parse("corrupt*"));
        rescanAndWait();

        assertThat(index.getChunks(notes))
                .extracting(Chunk::text)
                .containsExactly("Dentist moved to March 11.");
        assertThat(index.keywordSearch("appointment", SearchFilters.NONE, 5)).isEmpty();
        assertThat(index.getFile(docs.resolve("photo.bin"))).isEmpty();
        assertThat(index.getFile(docs.resolve("corrupt.txt"))).isEmpty();
        assertThat(indexer.getStatus().failedFiles()).isZero();
    }

    @Test
    void aRescanWithNoChangesIndexesNothingAgain() throws Exception {
        rescanAndWait();
        IndexedFile before = index.getFile(docs.resolve("lease.pdf")).orElseThrow();
        notifications.clear();
        rescanAndWait();
        assertThat(notifications)
                .extracting(IndexStatus::state)
                .doesNotContain(IndexerState.INDEXING);
        assertThat(index.getFile(docs.resolve("lease.pdf")).orElseThrow()).isEqualTo(before);
    }

    @Test
    void aDeletedFileRequestedDirectlyIsRemoved() throws Exception {
        rescanAndWait();
        Path lease = docs.resolve("lease.pdf");
        Files.delete(lease);
        indexer.requestUpdate(lease);
        await(() -> indexer.getStatus().isUpToDate());
        assertThat(index.getFile(lease)).isEmpty();
    }

    @Test
    void listenersHearUpToDateAfterASingleChange() throws Exception {
        rescanAndWait();
        notifications.clear();
        Path lease = docs.resolve("lease.pdf");
        Files.delete(lease);
        // The worker is waiting on the queue when the request arrives; it must still end by
        // telling the listeners the index is up to date, without anyone polling getStatus().
        indexer.requestUpdate(lease);
        await(() -> notifications.stream().anyMatch(IndexStatus::isUpToDate));
        assertThat(notifications.get(notifications.size() - 1).isUpToDate()).isTrue();
        assertThat(index.getFile(lease)).isEmpty();
    }

    @Test
    void aFileThatStaysLockedIsRetriedThenMarkedFailed() throws Exception {
        Files.writeString(docs.resolve("locked.txt"), "busy");
        rescanAndWait();
        IndexedFile locked = index.getFile(docs.resolve("locked.txt")).orElseThrow();
        assertThat(locked.extractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(locked.contentHash()).isEmpty();
    }

    @Test
    void rebuildDropsStaleEntriesAndIndexesEverythingAgain() throws Exception {
        rescanAndWait();
        Path stale = docs.resolve("vanished.txt");
        index.put(
                new IndexedFile(stale, "txt", 1, Instant.EPOCH, "", ExtractionStatus.OK),
                List.of(new Chunk(0, 0, "ghost", new float[0])));
        index.commit();
        indexer.rebuild();
        await(() -> indexer.getStatus().isUpToDate());
        assertThat(index.getFile(stale)).isEmpty();
        assertThat(index.keywordSearch("lease", SearchFilters.NONE, 5)).isNotEmpty();
        assertThat(index.countFiles()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ phase 2: embedding (F05)

    @Test
    void chunksGetTheirVectorsOnceTheFilesAreIndexed() throws Exception {
        rescanAndWait();
        assertThat(index.countChunksWithoutEmbeddings()).isZero();
        assertThat(indexer.getStatus().chunksWithoutEmbeddings()).isZero();
        // The lease has two chunks and the notes one; files without text have nothing to embed.
        assertThat(model.timesEmbedded("Rent is $1,850.")).isEqualTo(1);
        assertThat(model.timesEmbedded("The lease ends on August 31, 2026.")).isEqualTo(1);
        assertThat(model.timesEmbedded("Dentist appointment on March 4.")).isEqualTo(1);
        // After the texts, every indexed file's name is embedded with its folder's, in one call.
        assertThat(model.calls()).hasSize(3);
        assertThat(model.calls().get(2))
                .containsExactlyInAnyOrder(
                        "docs/lease.pdf", "sub/notes.txt", "docs/photo.bin", "docs/corrupt.txt");
        assertThat(index.findFilesWithoutNameEmbeddings(10)).isEmpty();
        assertThat(Indexer.nameText(Path.of("resume.pdf"))).isEqualTo("resume.pdf");

        float[] query = model.embed(List.of("Dentist appointment on March 4.")).get(0);
        assertThat(index.vectorSearch(query, SearchFilters.NONE, 1))
                .extracting(hit -> hit.file().path())
                .containsExactly(docs.resolve("sub/notes.txt"));
        // A file without text is found by what its name means.
        float[] name = model.embed(List.of("docs/photo.bin")).get(0);
        assertThat(index.vectorSearch(name, SearchFilters.NONE, 1))
                .extracting(hit -> hit.file().path())
                .containsExactly(docs.resolve("photo.bin"));

        // Embedding comes after indexing, and the listeners hear about both and then the end.
        List<IndexerState> states = notifications.stream().map(IndexStatus::state).toList();
        assertThat(states).contains(IndexerState.EMBEDDING);
        assertThat(states.lastIndexOf(IndexerState.INDEXING))
                .isLessThan(states.indexOf(IndexerState.EMBEDDING));
        IndexStatus last = notifications.get(notifications.size() - 1);
        assertThat(last.isUpToDate()).isTrue();
        assertThat(last.chunksWithoutEmbeddings()).isZero();
    }

    @Test
    void copiesOfAFileAreEmbeddedOnceAndAChangedFileAgain() throws Exception {
        Files.writeString(docs.resolve("sub/notes-copy.txt"), "Dentist appointment on March 4.");
        rescanAndWait();
        assertThat(model.timesEmbedded("Dentist appointment on March 4.")).isEqualTo(1);
        assertThat(index.countChunksWithoutEmbeddings()).isZero();

        Path notes = docs.resolve("sub/notes.txt");
        Instant before = index.getFile(notes).orElseThrow().modifiedTime();
        Files.writeString(notes, "Dentist moved to March 11.");
        Files.setLastModifiedTime(notes, FileTime.from(before.plusSeconds(90)));
        rescanAndWait();
        assertThat(model.timesEmbedded("Dentist moved to March 11.")).isEqualTo(1);
        assertThat(index.countChunksWithoutEmbeddings()).isZero();
        // The unchanged files were not embedded again.
        assertThat(model.timesEmbedded("Rent is $1,850.")).isEqualTo(1);
        assertThat(model.timesEmbedded("Dentist appointment on March 4.")).isEqualTo(1);
    }

    @Test
    void aContentThatCannotBeEmbeddedIsSkippedAndTheRestIsEmbedded() throws Exception {
        model.failOnTextContaining("Dentist");
        rescanAndWait();
        // The notes stay without a vector and are still found by keywords.
        assertThat(index.countChunksWithoutEmbeddings()).isEqualTo(1);
        assertThat(indexer.getStatus().isUpToDate()).isTrue();
        assertThat(indexer.getStatus().chunksWithoutEmbeddings()).isEqualTo(1);
        assertThat(index.keywordSearch("dentist", SearchFilters.NONE, 5)).hasSize(1);
        assertThat(index.findContentWithoutEmbeddings(10)).hasSize(1);

        // It isn't tried again in this run, even when other work comes in.
        long tries = model.timesEmbedded("Dentist appointment on March 4.");
        Files.writeString(docs.resolve("new.txt"), "A new file.");
        rescanAndWait();
        assertThat(model.timesEmbedded("A new file.")).isEqualTo(1);
        assertThat(model.timesEmbedded("Dentist appointment on March 4.")).isEqualTo(tries);
    }

    @Test
    void aModelThatCannotBeLoadedLeavesKeywordSearchWorking() throws Exception {
        model.failWith("The embedding model could not be loaded: no native library");
        for (int i = 0; i < 5; i++) {
            Files.writeString(docs.resolve("extra-" + i + ".txt"), "Extra file number " + i + ".");
        }
        rescanAndWait();
        assertThat(indexer.getStatus().isUpToDate()).isTrue();
        assertThat(indexer.getStatus().state()).isEqualTo(IndexerState.IDLE);
        assertThat(indexer.getStatus().chunksWithoutEmbeddings()).isEqualTo(8);
        assertThat(index.keywordSearch("lease", SearchFilters.NONE, 5)).isNotEmpty();
        assertThat(index.keywordSearch("extra", SearchFilters.NONE, 10)).hasSize(5);
        // After three contents in a row failed, the model is left alone for the rest of the run.
        assertThat(model.calls()).hasSize(Indexer.MAX_EMBEDDING_FAILURES);
    }

    @Test
    void stopCommitsAndReportsStopped() throws Exception {
        rescanAndWait();
        indexer.stop();
        assertThat(indexer.getStatus().state()).isEqualTo(IndexerState.STOPPED);
        assertThat(notifications.get(notifications.size() - 1).state())
                .isEqualTo(IndexerState.STOPPED);
    }
}
