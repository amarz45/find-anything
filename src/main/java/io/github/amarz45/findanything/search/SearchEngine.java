package io.github.amarz45.findanything.search;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.embedding.EmbeddingException;
import io.github.amarz45.findanything.embedding.EmbeddingModel;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instant search (F06): ranks the index's matches and groups them by file, so each file appears
 * once with its best passage. With semantic search on (F05), the query is also embedded, the chunks
 * closest to it in meaning are fetched, and the two lists are merged by rank; with it off, the
 * keyword hits are used as they are. Files in paused or unreachable folders are left out here, so
 * every caller, the agent's search tool included, skips them. Safe to call from several background
 * threads at once, because it only reads the index.
 */
public final class SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(SearchEngine.class);

    /**
     * Ask the index for this many chunk hits per wanted result, since a file can match many times.
     */
    static final int CHUNK_HITS_PER_RESULT = 5;

    /** The constant in reciprocal rank fusion; 60 is the usual choice. */
    static final int RRF_K = 60;

    /**
     * A chunk counts as a semantic match only when its cosine similarity to the query is at least
     * this. Vector search always returns the nearest chunks, however far they are, so without a
     * floor every query would list unrelated files. Measured with bge-small-en-v1.5 on the test
     * corpus: the nearest chunk scored at most 0.61 for 24 of 25 queries about things the corpus
     * doesn't hold, and 0.63 to 0.90 for 25 of 31 queries about things it does.
     */
    static final double MIN_SIMILARITY = 0.62;

    private final SearchIndex searchIndex;
    private final IndexConfig indexConfig;
    private final EmbeddingModel embeddingModel;
    private volatile boolean semanticSearchEnabled;

    /**
     * @param searchIndex the index to search
     * @param indexConfig the search folders, for which of them are active at the time of a search
     * @param embeddingModel embeds the query when semantic search is on; the same model as the one
     *     that embedded the chunks
     */
    public SearchEngine(
            SearchIndex searchIndex, IndexConfig indexConfig, EmbeddingModel embeddingModel) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    }

    /**
     * Ranks files for a query. An empty query lists the indexed files, newest first, with no best
     * chunk. The filters apply inside the index query. Files in paused or unreachable folders are
     * dropped from what the index returns, so there can be fewer than {@code limit} results even
     * when more files match.
     *
     * @return at most {@code limit} results, best first
     */
    public List<SearchResult> search(String query, SearchFilters searchFilters, int limit)
            throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(searchFilters, "searchFilters");
        if (query.isBlank()) {
            return searchIndex.listFiles(searchFilters, limit).stream()
                    .filter(file -> indexConfig.isInActiveFolder(file.path()))
                    .map(file -> new SearchResult(file, null, 0))
                    .toList();
        }
        int chunkHits = limit * CHUNK_HITS_PER_RESULT;
        List<ChunkHit> keywordHits = searchIndex.keywordSearch(query, searchFilters, chunkHits);
        List<ChunkHit> semanticHits =
                semanticSearchEnabled ? semanticHits(query, searchFilters, chunkHits) : List.of();
        List<ChunkHit> ranked = fuseRankings(keywordHits, semanticHits);
        Map<Path, SearchResult> byFile = new LinkedHashMap<>();
        for (ChunkHit hit : ranked) {
            if (!indexConfig.isInActiveFolder(hit.file().path())) {
                continue;
            }
            byFile.putIfAbsent(
                    hit.file().path(), new SearchResult(hit.file(), hit.chunk(), hit.score()));
            if (byFile.size() == limit) {
                break;
            }
        }
        return List.copyOf(byFile.values());
    }

    /**
     * Turns semantic search on or off. It is off until the facade turns it on; the CLI's {@code
     * --keyword-only} turns it off, and so does the engine itself when the embedding model fails.
     */
    public void setSemanticSearchEnabled(boolean enabled) {
        this.semanticSearchEnabled = enabled;
    }

    public boolean isSemanticSearchEnabled() {
        return semanticSearchEnabled;
    }

    /**
     * The chunks close enough in meaning to the query, nearest first. When the model can't embed
     * the query (it couldn't be loaded, say), semantic search is turned off with a warning and this
     * search goes on with keywords only.
     */
    private List<ChunkHit> semanticHits(String query, SearchFilters searchFilters, int limit)
            throws IOException {
        try {
            float[] queryVector = embeddingModel.embed(List.of(query)).get(0);
            return searchIndex.vectorSearch(queryVector, searchFilters, limit).stream()
                    .filter(hit -> hit.score() >= MIN_SIMILARITY)
                    .toList();
        } catch (EmbeddingException | IllegalArgumentException e) {
            // IllegalArgumentException: the index's vectors come from a model with another length.
            semanticSearchEnabled = false;
            log.warn(
                    "Semantic search is unavailable, so search uses keywords only: {}",
                    e.getMessage());
            return List.of();
        }
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
