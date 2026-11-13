package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.llm.ToolSpec.Parameter;
import io.github.amarz45.findanything.search.SearchEngine;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The agent's search command (F07): runs the same instant search as the search bar, with the same
 * filters (F04), and gives the model each matching file with its best passage. The model plans with
 * it: it searches, reads the results, and searches again with other words or filters.
 *
 * <p>A passage is given once per request. When a later search of the same request finds it again,
 * the file is still listed, with a line naming the earlier search in place of the passage's text.
 *
 * <p>Files with the same content share one entry and one passage: the best-ranked copy is listed
 * with its details, and the other copies as paths under it.
 */
public final class SearchTool implements Tool {

    /** The tool's name, as the model calls it. */
    public static final String NAME = "search";

    /**
     * The file types the model can filter on: the formats whose text is indexed. Other files are
     * found by name, without a type filter.
     */
    static final List<String> FILE_TYPES = List.of("pdf", "docx", "pptx", "txt", "md");

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;

    private static final String QUERY = "query";
    private static final String FILE_TYPE = "file_type";
    private static final String MODIFIED_AFTER = "modified_after";
    private static final String MODIFIED_BEFORE = "modified_before";
    private static final String LIMIT = "limit";

    private static final ToolSpec SPEC =
            new ToolSpec(
                    NAME,
                    "Searches the user's indexed files by keywords, in the files' text and in"
                            + " their names, and returns the best-matching files, each with its"
                            + " best-matching passage. The words are matched one by one; case and"
                            + " accents are ignored.",
                    List.of(
                            Parameter.string(
                                    QUERY,
                                    "The keywords to look for. An empty string lists the most"
                                            + " recently modified files that pass the filters.",
                                    true),
                            Parameter.oneOf(
                                    FILE_TYPE,
                                    FILE_TYPES,
                                    "Only files of this type (pptx is PowerPoint slides, docx is"
                                            + " Word). Leave it out to search every file.",
                                    false),
                            Parameter.string(
                                    MODIFIED_AFTER,
                                    "Only files modified on or after this date, as YYYY-MM-DD.",
                                    false),
                            Parameter.string(
                                    MODIFIED_BEFORE,
                                    "Only files modified on or before this date, as YYYY-MM-DD.",
                                    false),
                            Parameter.integer(
                                    LIMIT,
                                    "The most files to return, from 1 to "
                                            + MAX_LIMIT
                                            + " (default "
                                            + DEFAULT_LIMIT
                                            + ").",
                                    false)));

    private final SearchEngine searchEngine;

    public SearchTool(SearchEngine searchEngine) {
        this.searchEngine = Objects.requireNonNull(searchEngine, "searchEngine");
    }

    @Override
    public ToolSpec getSpec() {
        return SPEC;
    }

    @Override
    public List<String> validate(Map<String, Object> arguments) {
        List<String> errors = new ArrayList<>();
        LocalDate after = parseDate(arguments, MODIFIED_AFTER, errors);
        LocalDate before = parseDate(arguments, MODIFIED_BEFORE, errors);
        if (after != null && before != null && after.isAfter(before)) {
            errors.add(
                    "\""
                            + MODIFIED_AFTER
                            + "\" ("
                            + after
                            + ") is later than \""
                            + MODIFIED_BEFORE
                            + "\" ("
                            + before
                            + ").");
        }
        if (arguments.get(LIMIT) instanceof Number number) {
            long limit = number.longValue();
            if (limit < 1 || limit > MAX_LIMIT) {
                errors.add(
                        "Parameter \""
                                + LIMIT
                                + "\" must be from 1 to "
                                + MAX_LIMIT
                                + ", but was "
                                + limit
                                + ".");
            }
        }
        return errors;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ShownPassages shownPassages) {
        String query = ((String) arguments.get(QUERY)).strip();
        String fileType = (String) arguments.get(FILE_TYPE);
        SearchFilters searchFilters =
                new SearchFilters(
                        fileType == null ? Set.of() : Set.of(fileType),
                        parseDate(arguments, MODIFIED_AFTER, new ArrayList<>()),
                        parseDate(arguments, MODIFIED_BEFORE, new ArrayList<>()),
                        -1,
                        -1,
                        null);
        int limit =
                arguments.get(LIMIT) instanceof Number number ? number.intValue() : DEFAULT_LIMIT;
        List<SearchResult> results;
        try {
            results = searchEngine.search(query, searchFilters, limit);
        } catch (IOException e) {
            return ToolResult.failure("The search could not be run: " + e.getMessage());
        }
        if (results.isEmpty()) {
            return ToolResult.success(describeNoResults(query, searchFilters), List.of());
        }
        List<Path> sourceFiles = results.stream().map(result -> result.file().path()).toList();
        List<List<SearchResult>> entries = groupCopies(results);
        String content = describe(query, entries, shownPassages);
        // Recorded only after the text is written, so a result never refers to itself. A copy is
        // recorded with its entry's passage, which it holds at the same position.
        List<SearchResult> given = new ArrayList<>();
        for (List<SearchResult> entry : entries) {
            Chunk passage = entry.get(0).bestChunk();
            for (SearchResult result : entry) {
                given.add(new SearchResult(result.file(), passage, result.score()));
            }
        }
        shownPassages.record(query, given);
        return ToolResult.success(content, sourceFiles);
    }

    /** The date a parameter gives, or null when it is absent; a malformed one adds an error. */
    private static LocalDate parseDate(
            Map<String, Object> arguments, String name, List<String> errors) {
        if (!(arguments.get(name) instanceof String text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            errors.add(
                    "Parameter \""
                            + name
                            + "\" must be a date written as YYYY-MM-DD, such as 2025-01-31, but"
                            + " was \""
                            + text
                            + "\".");
            return null;
        }
    }

    private static String describeNoResults(String query, SearchFilters searchFilters) {
        String what = query.isEmpty() ? "No indexed files" : "No files match \"" + query + "\"";
        return searchFilters.isEmpty()
                ? what + ". Try fewer or different keywords."
                : what + " with these filters. Try other keywords, or search without a filter.";
    }

    /**
     * Puts the results into entries, best first. Each file gets its own entry, except that a file
     * with the same content as a better-ranked one joins that file's entry, so the passage is sent
     * once for all the copies. Only files with a passage are joined: a file matched by its name
     * only, or listed for an empty query, keeps its own entry.
     */
    private static List<List<SearchResult>> groupCopies(List<SearchResult> results) {
        List<List<SearchResult>> entries = new ArrayList<>();
        Map<String, List<SearchResult>> byContentHash = new HashMap<>();
        for (SearchResult result : results) {
            String contentHash = result.file().contentHash();
            boolean canJoin = result.bestChunk() != null && !contentHash.isEmpty();
            List<SearchResult> entry = canJoin ? byContentHash.get(contentHash) : null;
            if (entry == null) {
                entry = new ArrayList<>();
                entries.add(entry);
                if (canJoin) {
                    byContentHash.put(contentHash, entry);
                }
            }
            entry.add(result);
        }
        return entries;
    }

    /**
     * Lists the entries for the model: the path to cite, the file's details, the paths of its
     * copies, and the passage.
     *
     * <p>A passage's text always starts on the line after its "Best passage" label. A passage the
     * request was already given isn't repeated: the label line itself names the earlier search.
     * Only this method writes on a label line, so the text of a file can't be taken for that note
     * by its place.
     */
    private static String describe(
            String query, List<List<SearchResult>> entries, ShownPassages shownPassages) {
        StringBuilder text = new StringBuilder();
        int files = entries.stream().mapToInt(List::size).sum();
        text.append(files).append(files == 1 ? " file" : " files");
        text.append(
                query.isEmpty()
                        ? ", most recently modified first:"
                        : " for \"" + query + "\", best match first:");
        int number = 0;
        for (List<SearchResult> entry : entries) {
            SearchResult result = entry.get(0);
            IndexedFile file = result.file();
            number++;
            text.append("\n\n").append(number).append(". ").append(file.path());
            text.append("\n   ")
                    .append(file.fileType().isEmpty() ? "no extension" : file.fileType())
                    .append(", ")
                    .append(file.sizeBytes())
                    .append(" bytes, modified ")
                    .append(file.modifiedTime().atZone(ZoneId.systemDefault()).toLocalDate());
            for (SearchResult copy : entry.subList(1, entry.size())) {
                text.append("\n   Same content at: ").append(copy.file().path());
            }
            Chunk chunk = result.bestChunk();
            if (chunk != null) {
                text.append("\n   Best passage");
                if (chunk.pageNumber() > 0) {
                    text.append(file.fileType().equals("pptx") ? " (slide " : " (page ")
                            .append(chunk.pageNumber())
                            .append(")");
                }
                Optional<String> earlierQuery = shownPassages.queryThatGave(file.path(), chunk);
                if (earlierQuery.isPresent()) {
                    text.append(": already given above, in the results for \"")
                            .append(earlierQuery.get())
                            .append("\".");
                } else {
                    text.append(":\n").append(chunk.text().strip());
                }
            } else if (!query.isEmpty()) {
                text.append("\n   Matched by its name only; none of its text is indexed.");
            }
        }
        return text.toString();
    }
}
