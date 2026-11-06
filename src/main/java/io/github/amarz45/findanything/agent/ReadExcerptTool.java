package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.llm.ToolSpec.Parameter;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The agent's reading command (F08): returns part of an indexed file's extracted text, with the
 * page or slide each passage is on, so the model can answer and cite. It reads only from the index,
 * never the file system, so the model can reach nothing that isn't indexed. An excerpt holds at
 * most {@code maxExcerptChars} characters, so a model is sent excerpts, never whole files; the
 * model asks for the next part when it needs more.
 */
public final class ReadExcerptTool implements Tool {

    /** The tool's name, as the model calls it. */
    public static final String NAME = "read_excerpt";

    private static final String FILE = "file";
    private static final String PAGE = "page";
    private static final String POSITION = "position";

    private static final ToolSpec SPEC =
            new ToolSpec(
                    NAME,
                    "Reads an excerpt of one indexed file's text, in reading order, marking the"
                            + " page or slide of each passage. A long file comes in parts: the"
                            + " excerpt ends by saying how to read the next part.",
                    List.of(
                            Parameter.string(
                                    FILE,
                                    "The file's full path, exactly as the search tool gave it.",
                                    true),
                            Parameter.integer(
                                    PAGE,
                                    "Start at this page (PDF) or slide (PowerPoint), counted from"
                                            + " 1. Leave it out to start at the beginning.",
                                    false),
                            Parameter.integer(
                                    POSITION,
                                    "Start at this position, a number given at the end of an"
                                            + " earlier excerpt of the same file. Don't give both"
                                            + " a page and a position.",
                                    false)));

    private final SearchIndex searchIndex;
    private final IndexConfig indexConfig;
    private final int maxExcerptChars;

    /**
     * @param searchIndex the index to read from
     * @param indexConfig the search folders; a file in a paused or unreachable folder isn't read
     * @param maxExcerptChars the most characters of file text one excerpt may hold; at least 1
     */
    public ReadExcerptTool(SearchIndex searchIndex, IndexConfig indexConfig, int maxExcerptChars) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.indexConfig = Objects.requireNonNull(indexConfig, "indexConfig");
        if (maxExcerptChars < 1) {
            throw new IllegalArgumentException("maxExcerptChars must be at least 1");
        }
        this.maxExcerptChars = maxExcerptChars;
    }

    @Override
    public ToolSpec getSpec() {
        return SPEC;
    }

    @Override
    public List<String> validate(Map<String, Object> arguments) {
        List<String> errors = new ArrayList<>();
        String file = (String) arguments.get(FILE);
        try {
            if (!Path.of(file).isAbsolute()) {
                errors.add(
                        "Parameter \""
                                + FILE
                                + "\" must be a full path as the search tool gave it, but was \""
                                + file
                                + "\".");
            }
        } catch (InvalidPathException e) {
            errors.add("Parameter \"" + FILE + "\" isn't a valid path: \"" + file + "\".");
        }
        Object page = arguments.get(PAGE);
        Object position = arguments.get(POSITION);
        if (page != null && position != null) {
            errors.add("Give \"" + PAGE + "\" or \"" + POSITION + "\", not both.");
        }
        if (page instanceof Number number && number.longValue() < 1) {
            errors.add("Parameter \"" + PAGE + "\" must be 1 or more, but was " + number + ".");
        }
        if (position instanceof Number number && number.longValue() < 0) {
            errors.add("Parameter \"" + POSITION + "\" must be 0 or more, but was " + number + ".");
        }
        return errors;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Path path = Path.of((String) arguments.get(FILE)).normalize();
        Optional<IndexedFile> indexed;
        List<Chunk> chunks;
        try {
            indexed = searchIndex.getFile(path);
            chunks = searchIndex.getChunks(path);
        } catch (IOException e) {
            return ToolResult.failure("The file could not be read: " + e.getMessage());
        }
        if (indexed.isEmpty()) {
            return ToolResult.failure(
                    "No indexed file has the path "
                            + path
                            + ". Use a path exactly as the search tool gave it.");
        }
        if (!indexConfig.isInActiveFolder(path)) {
            return ToolResult.failure(
                    path
                            + " is in a search folder that is paused or unavailable, so it can't be"
                            + " read now.");
        }
        IndexedFile file = indexed.get();
        if (chunks.isEmpty()) {
            return ToolResult.failure(path + " can't be read: " + whyNoText(file) + ".");
        }
        String unit = file.fileType().equals("pptx") ? "slide" : "page";
        int start = 0;
        if (arguments.get(POSITION) instanceof Number number) {
            start = number.intValue();
            if (start >= chunks.size()) {
                return ToolResult.failure(
                        path
                                + " has no position "
                                + start
                                + "; its positions are 0 to "
                                + (chunks.size() - 1)
                                + ".");
            }
        } else if (arguments.get(PAGE) instanceof Number number) {
            int page = number.intValue();
            start = firstChunkOfPage(chunks, page);
            if (start < 0) {
                return ToolResult.failure(path + " " + describePages(chunks, unit, page) + ".");
            }
        }
        return ToolResult.success(excerpt(file, chunks, start, unit), List.of(path));
    }

    private static String whyNoText(IndexedFile file) {
        return switch (file.extractionStatus()) {
            case METADATA_ONLY -> "its format isn't supported, so it is indexed by name only";
            case NO_TEXT -> "it has no text layer (it may be a scan)";
            case FAILED -> "its text could not be extracted (corrupt or password-protected)";
            case OK, PARTIAL -> "it has no text";
        };
    }

    private static int firstChunkOfPage(List<Chunk> chunks, int page) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).pageNumber() == page) {
                return i;
            }
        }
        return -1;
    }

    /** Why a page can't be started at: the file has no page numbers, or not that one. */
    private static String describePages(List<Chunk> chunks, String unit, int page) {
        int last = chunks.get(chunks.size() - 1).pageNumber();
        if (last == 0) {
            return "has no " + unit + " numbers; leave \"" + PAGE + "\" out";
        }
        return "has no text on "
                + unit
                + " "
                + page
                + "; its "
                + unit
                + "s with text are between "
                + chunks.get(0).pageNumber()
                + " and "
                + last;
    }

    /**
     * Joins whole chunks from {@code start} while they fit in {@code maxExcerptChars}, with a line
     * naming each numbered page, and ends by saying whether more follows. The first chunk is always
     * included, cut short if it alone is too long.
     */
    private String excerpt(IndexedFile file, List<Chunk> chunks, int start, String unit) {
        StringBuilder text = new StringBuilder("Excerpt of ").append(file.path()).append(":\n\n");
        int used = 0;
        int page = 0;
        int next = start;
        while (next < chunks.size()) {
            Chunk chunk = chunks.get(next);
            String chunkText = chunk.text();
            if (next > start && used + chunkText.length() > maxExcerptChars) {
                break;
            }
            if (chunkText.length() > maxExcerptChars) {
                chunkText = chunkText.substring(0, maxExcerptChars) + "…";
            }
            if (chunk.pageNumber() != page) {
                page = chunk.pageNumber();
                text.append(next > start ? "\n\n" : "");
                text.append('[').append(unit).append(' ').append(page).append("]\n");
            } else if (next > start) {
                text.append('\n');
            }
            text.append(chunkText);
            used += chunkText.length();
            next++;
        }
        text.append("\n\n");
        if (next < chunks.size()) {
            text.append("[More of this file follows. To read on, call ")
                    .append(NAME)
                    .append(" with the same file and position ")
                    .append(next)
                    .append(".]");
        } else {
            text.append(
                    switch (file.extractionStatus()) {
                        case PARTIAL ->
                                "[End of the indexed text. The file is longer, but only its first"
                                        + " part is indexed.]";
                        default -> "[End of the file's text.]";
                    });
        }
        return text.toString();
    }
}
