package io.github.amarz45.findanything.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ChunkHit;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchEngineTest {

    static final Path ROOT = Path.of("data").toAbsolutePath();

    @TempDir Path folder;

    static IndexedFile file(String name, Instant modified) {
        Path path = ROOT.resolve(name);
        return new IndexedFile(
                path, IndexedFile.fileTypeOf(path), 100, modified, "h", ExtractionStatus.OK);
    }

    static Chunk chunk(int position, String text) {
        return new Chunk(position, position + 1, text, new float[0]);
    }

    /** A configuration with these search folders; with none, {@code ROOT} is the one folder. */
    IndexConfig config(SearchFolder... searchFolders) {
        List<SearchFolder> folders =
                searchFolders.length == 0
                        ? List.of(new SearchFolder(ROOT, false, true, List.of()))
                        : List.of(searchFolders);
        return new IndexConfig(folder, folders, List.of());
    }

    @Test
    void leavesOutFilesInPausedOrUnavailableFolders() throws Exception {
        SearchFolder docs = new SearchFolder(ROOT.resolve("docs"), false, true, List.of());
        SearchFolder archive = new SearchFolder(ROOT.resolve("archive"), true, true, List.of());
        SearchFolder usb = new SearchFolder(ROOT.resolve("usb"), false, false, List.of());
        try (SearchIndex index = SearchIndex.open(folder)) {
            // The paused folder holds the best match and the newest file.
            index.put(
                    file("archive/old.txt", Instant.ofEpochSecond(9)),
                    List.of(chunk(0, "merge sort merge sort merge sort")));
            index.put(
                    file("docs/a.txt", Instant.ofEpochSecond(1)),
                    List.of(chunk(0, "a note about merge sort")));
            index.put(
                    file("usb/b.txt", Instant.ofEpochSecond(2)),
                    List.of(chunk(0, "merge sort on a stick")));
            index.put(
                    file("removed/c.txt", Instant.ofEpochSecond(3)),
                    List.of(chunk(0, "merge sort in a folder that was removed")));
            index.commit();
            SearchEngine engine = new SearchEngine(index, config(docs, archive, usb));

            assertThat(engine.search("merge sort", SearchFilters.NONE, 10))
                    .extracting(r -> r.file().path())
                    .containsExactly(ROOT.resolve("docs/a.txt"));
            assertThat(engine.search("", SearchFilters.NONE, 10))
                    .extracting(r -> r.file().path())
                    .containsExactly(ROOT.resolve("docs/a.txt"));
            // The limit counts the files that are returned, not the ones passed over.
            assertThat(engine.search("merge sort", SearchFilters.NONE, 1))
                    .extracting(r -> r.file().path())
                    .containsExactly(ROOT.resolve("docs/a.txt"));

            // The folders are read at the time of each search.
            archive.setPaused(false);
            assertThat(engine.search("merge sort", SearchFilters.NONE, 10))
                    .extracting(r -> r.file().path())
                    .containsExactly(ROOT.resolve("archive/old.txt"), ROOT.resolve("docs/a.txt"));
        }
    }

    @Test
    void groupsHitsByFileKeepingTheBestChunk() throws Exception {
        try (SearchIndex index = SearchIndex.open(folder)) {
            index.put(
                    file("a.pdf", Instant.ofEpochSecond(1)),
                    List.of(
                            chunk(0, "sorting intro"),
                            chunk(1, "merge sort merge sort merge sort"),
                            chunk(2, "merge sort again")));
            index.put(
                    file("b.txt", Instant.ofEpochSecond(2)),
                    List.of(chunk(0, "a note about merge sort")));
            index.put(file("c.txt", Instant.ofEpochSecond(3)), List.of(chunk(0, "unrelated")));
            index.commit();
            SearchEngine engine = new SearchEngine(index, config());

            List<SearchResult> results = engine.search("merge sort", SearchFilters.NONE, 10);
            assertThat(results)
                    .extracting(r -> r.file().path().getFileName().toString())
                    .containsExactly("a.pdf", "b.txt");
            assertThat(results.get(0).bestChunk().position()).isEqualTo(1);
            assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());

            assertThat(engine.search("merge sort", SearchFilters.NONE, 1)).hasSize(1);

            List<SearchResult> listing = engine.search("  ", SearchFilters.NONE, 10);
            assertThat(listing)
                    .extracting(r -> r.file().path().getFileName().toString())
                    .containsExactly("c.txt", "b.txt", "a.pdf");
            assertThat(listing).allSatisfy(r -> assertThat(r.bestChunk()).isNull());
            assertThat(engine.isSemanticSearchEnabled()).isFalse();
        }
    }

    @Test
    void fusesTwoRankingsByReciprocalRank() throws Exception {
        try (SearchIndex index = SearchIndex.open(folder)) {
            SearchEngine engine = new SearchEngine(index, config());
            IndexedFile a = file("a.txt", Instant.EPOCH);
            IndexedFile b = file("b.txt", Instant.EPOCH);
            IndexedFile c = file("c.txt", Instant.EPOCH);
            ChunkHit a0 = new ChunkHit(a, chunk(0, "a"), 9);
            ChunkHit b0 = new ChunkHit(b, chunk(0, "b"), 8);
            ChunkHit c0 = new ChunkHit(c, chunk(0, "c"), 7);
            List<ChunkHit> keyword = List.of(a0, b0, c0);

            assertThat(engine.fuseRankings(keyword, List.of())).isSameAs(keyword);

            // c is first in the semantic list and b second in both: both overtake a, and c edges
            // out b
            List<ChunkHit> fused = engine.fuseRankings(keyword, List.of(c0, b0));
            assertThat(fused)
                    .extracting(h -> h.file().path().getFileName().toString())
                    .containsExactly("c.txt", "b.txt", "a.txt");
            double expectedC = 1.0 / (SearchEngine.RRF_K + 3) + 1.0 / (SearchEngine.RRF_K + 1);
            assertThat(fused.get(0).score())
                    .isCloseTo(expectedC, org.assertj.core.data.Offset.offset(1e-12));
        }
    }
}
