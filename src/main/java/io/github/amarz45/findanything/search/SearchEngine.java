package io.github.amarz45.findanything.search;

import io.github.amarz45.findanything.storage.ChunkHit;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Instant search (F06): ranks the index's matches and groups them by file, so each file appears
 * once with its best passage. Safe to call from several background threads at once, because it only
 * reads the index. Semantic search (F05) is off until an embedding model is available; with it off,
 * the keyword hits are used as they are.
 */
public final class SearchEngine {

    /**
     * Ask the index for this many chunk hits per wanted result, since a file can match many times.
     */
    static final int CHUNK_HITS_PER_RESULT = 5;

    /** The constant in reciprocal rank fusion; 60 is the usual choice. */
    static final int RRF_K = 60;

    private final SearchIndex searchIndex;
    private volatile boolean semanticSearchEnabled;

    public SearchEngine(SearchIndex searchIndex) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
    }

    /**
     * Ranks files for a query. An empty query lists the indexed files, newest first, with no best
     * chunk. The filters apply inside the index query.
     *
     * @return at most {@code limit} results, best first
     */
    public List<SearchResult> search(String query, SearchFilters searchFilters, int limit)
            throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(searchFilters, "searchFilters");
        if (query.isBlank()) {
            return searchIndex.listFiles(searchFilters, limit).stream()
                    .map(file -> new SearchResult(file, null, 0))
                    .toList();
        }
        List<ChunkHit> keywordHits =
                searchIndex.keywordSearch(query, searchFilters, limit * CHUNK_HITS_PER_RESULT);
        List<ChunkHit> ranked = fuseRankings(keywordHits, List.of());
        Map<Path, SearchResult> byFile = new LinkedHashMap<>();
        for (ChunkHit hit : ranked) {
            byFile.putIfAbsent(
                    hit.file().path(), new SearchResult(hit.file(), hit.chunk(), hit.score()));
            if (byFile.size() == limit) {
                break;
            }
        }
        return List.copyOf(byFile.values());
    }

    /**
     * Turns semantic search on or off. It is off by default and stays off while there is no
     * embedding model (F05); the CLI's {@code --keyword-only} also turns it off.
     */
    public void setSemanticSearchEnabled(boolean enabled) {
        this.semanticSearchEnabled = enabled;
    }

    public boolean isSemanticSearchEnabled() {
        return semanticSearchEnabled;
    }

    /**
     * Merges two ranked lists by rank with reciprocal rank fusion: each hit scores the sum of
     * {@code 1 / (k + rank)} over the lists it appears in. With no semantic hits, the keyword hits
     * are returned as they are.
     */
    List<ChunkHit> fuseRankings(List<ChunkHit> keywordHits, List<ChunkHit> semanticHits) {
        if (semanticHits.isEmpty()) {
            return keywordHits;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, ChunkHit> hits = new LinkedHashMap<>();
        for (List<ChunkHit> list : List.of(keywordHits, semanticHits)) {
            for (int rank = 0; rank < list.size(); rank++) {
                ChunkHit hit = list.get(rank);
                String key = keyOf(hit);
                hits.putIfAbsent(key, hit);
                scores.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        List<ChunkHit> fused = new ArrayList<>();
        scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(
                        entry -> {
                            ChunkHit hit = hits.get(entry.getKey());
                            fused.add(new ChunkHit(hit.file(), hit.chunk(), entry.getValue()));
                        });
        return fused;
    }

    private static String keyOf(ChunkHit hit) {
        IndexedFile file = hit.file();
        int position = hit.chunk() == null ? -1 : hit.chunk().position();
        return file.path() + "#" + position;
    }
}
