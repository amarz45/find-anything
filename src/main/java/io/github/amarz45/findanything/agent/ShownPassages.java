package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The passages the search tool has given the model during one request, so a later search of the
 * same request can name the earlier search in place of a passage, and not send its text again.
 * {@link AgentController} creates one for each request and passes it with every tool call, because
 * the registry and its tools are shared by all requests.
 *
 * <p>Only passages the model can see are recorded. A cloud model is sent none of a search result
 * that holds a file from a "local model only" folder (F14), so such a result records nothing and a
 * later search gives its passages in full.
 *
 * <p>Used by one request, on that request's thread.
 */
public final class ShownPassages {

    private final Predicate<Path> withheld;
    private final Map<Passage, String> queries = new HashMap<>();

    /**
     * @param withheld whether a file's text is withheld from the model this request talks to
     */
    public ShownPassages(Predicate<Path> withheld) {
        this.withheld = Objects.requireNonNull(withheld, "withheld");
    }

    /**
     * The query of the search that gave this passage to the model earlier in the request, or empty
     * when the model hasn't been given it.
     */
    public Optional<String> queryThatGave(Path file, Chunk chunk) {
        return Optional.ofNullable(queries.get(new Passage(file, chunk.position())));
    }

    /**
     * Records the best passages of one search's results as given to the model. A passage keeps the
     * query of the first search that gave it. Nothing is recorded when any of the results' files is
     * withheld, because the model is then sent none of the result.
     */
    public void record(String query, List<SearchResult> results) {
        Objects.requireNonNull(query, "query");
        if (results.stream().anyMatch(result -> withheld.test(result.file().path()))) {
            return;
        }
        for (SearchResult result : results) {
            Chunk chunk = result.bestChunk();
            if (chunk != null) {
                queries.putIfAbsent(new Passage(result.file().path(), chunk.position()), query);
            }
        }
    }

    /** What identifies a passage: its file and its chunk's position in the file. */
    private record Passage(Path file, int position) {}
}
