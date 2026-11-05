package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.LlmResponse;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's final reply into an {@link Answer} with its {@link Citation}s (F08, F09). The
 * answer format is plain lines, so file paths and quotes need no escaping:
 *
 * <pre>
 * Your lease ends on August 31, 2026 [1].
 *
 * CITATIONS:
 * [1] file: /home/me/Documents/lease.pdf
 * page: 2
 * quote: The lease ends on August 31, 2026.
 * </pre>
 *
 * <p>The text before the {@code CITATIONS:} line is the answer; its {@code [n]} markers point at
 * the numbered entries. {@code page} is left out for a file without pages. A reply with no markers
 * and no entries is an answer without citations, such as a clarifying question.
 *
 * <p>The parser forgives layout (Markdown decoration, code fences, a quote in quotation marks or
 * over several lines, entries numbered out of order) but not missing substance: a marker without an
 * entry, or an entry without a usable file, is an {@link AnswerFormatException}.
 */
public final class ResponseParser {

    /** The line that separates the answer's text from its citations. */
    public static final String CITATIONS_HEADER = "CITATIONS:";

    private static final Pattern HEADER_LINE =
            Pattern.compile(
                    "^[\\s#*_]*(citations|sources)\\s*:?[\\s*_]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_LABEL =
            Pattern.compile("^[\\s#*_]*answer\\s*:[\\s*_]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s*```\\w*\\s*$");
    private static final Pattern ENTRY_START =
            Pattern.compile("^\\s*(?:[-*]\\s*)?\\[(\\d+)]\\s*(.*)$");
    private static final Pattern FIELD_LINE =
            Pattern.compile(
                    "^\\s*(?:[-*]\\s*)?[*_]*(file|page|slide|quote)[*_]*\\s*:[*_]*\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** One numbered entry of the citations section, as it is being read. */
    private static final class Entry {
        final int number;
        String file;
        String page;
        StringBuilder quote;

        Entry(int number) {
            this.number = number;
        }
    }

    /**
     * Parses a final reply.
     *
     * @return the answer's text with its markers numbered 1, 2, 3 in the order of the citations,
     *     and the citations, not verified yet; {@code sourceFiles} and {@code notices} are empty
     * @throws AnswerFormatException when the reply is empty, a marker has no entry, an entry is
     *     listed twice, or an entry has no file, a path that isn't a full path, or a page that
     *     isn't a number
     */
    public Answer parseAnswer(LlmResponse response) throws AnswerFormatException {
        List<String> lines =
                response.text()
                        .lines()
                        .filter(line -> !FENCE_LINE.matcher(line).matches())
                        .toList();
        int header = -1;
        for (int i = 0; i < lines.size() && header < 0; i++) {
            if (HEADER_LINE.matcher(lines.get(i)).matches()) {
                header = i;
            }
        }
        String text = String.join("\n", header < 0 ? lines : lines.subList(0, header)).strip();
        text = ANSWER_LABEL.matcher(text).replaceFirst("").strip();
        List<Entry> entries =
                header < 0 ? List.of() : readEntries(lines.subList(header + 1, lines.size()));

        List<String> problems = new ArrayList<>();
        if (text.isEmpty()) {
            problems.add(
                    entries.isEmpty()
                            ? "The reply is empty."
                            : "There is no answer text before the " + CITATIONS_HEADER + " line.");
        }
        Map<Integer, Integer> newNumbers = new LinkedHashMap<>();
        List<Citation> citations = new ArrayList<>();
        for (Entry entry : entries) {
            if (newNumbers.containsKey(entry.number)) {
                problems.add("Citation [" + entry.number + "] is listed twice.");
                continue;
            }
            newNumbers.put(entry.number, newNumbers.size() + 1);
            Citation citation = toCitation(entry, problems);
            if (citation != null) {
                citations.add(citation);
            }
        }
        text = renumberMarkers(text, newNumbers, problems);
        if (!problems.isEmpty()) {
            throw new AnswerFormatException(String.join(" ", problems));
        }
        return new Answer(text, citations, List.of(), List.of());
    }

    /** Reads the numbered entries: a {@code [n]} line starts one, and field lines fill it. */
    private static List<Entry> readEntries(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        boolean inQuote = false;
        for (String line : lines) {
            if (line.isBlank()) {
                inQuote = false;
                continue;
            }
            Matcher start = ENTRY_START.matcher(line);
            if (start.matches()) {
                current = new Entry(Integer.parseInt(truncate(start.group(1))));
                entries.add(current);
                inQuote = false;
                line = start.group(2);
                if (line.isBlank()) {
                    continue;
                }
            }
            if (current == null) {
                continue; // text between the header and the first entry, such as "none"
            }
            Matcher field = FIELD_LINE.matcher(line);
            if (field.matches()) {
                String name = field.group(1).toLowerCase(Locale.ROOT);
                String value = field.group(2).strip();
                inQuote = name.equals("quote");
                switch (name) {
                    case "file" -> current.file = value;
                    case "quote" -> current.quote = new StringBuilder(value);
                    default -> current.page = value; // page or slide
                }
            } else if (inQuote) {
                current.quote.append(' ').append(line.strip());
            }
        }
        return entries;
    }

    /**
     * Checks one entry and makes its citation; adds to {@code problems} and returns null if bad.
     */
    private static Citation toCitation(Entry entry, List<String> problems) {
        String label = "Citation [" + entry.number + "]";
        String file = entry.file == null ? "" : unwrap(entry.file);
        if (file.isEmpty()) {
            problems.add(label + " has no \"file:\" line.");
            return null;
        }
        Path path;
        try {
            path = Path.of(file);
        } catch (InvalidPathException e) {
            problems.add(label + " has a file that isn't a valid path: " + file + ".");
            return null;
        }
        if (!path.isAbsolute()) {
            problems.add(
                    label
                            + " must give the file's full path as the tools gave it, but has "
                            + file
                            + ".");
            return null;
        }
        int pageNumber = 0;
        if (entry.page != null && !entry.page.isBlank()) {
            Matcher number = NUMBER.matcher(entry.page);
            if (number.find()) {
                pageNumber = Integer.parseInt(truncate(number.group()));
            } else if (!isNoPage(entry.page)) {
                problems.add(label + " has a page that isn't a number: " + entry.page + ".");
                return null;
            }
        }
        String quote = entry.quote == null ? "" : unwrap(entry.quote.toString());
        return new Citation(path, pageNumber, quote);
    }

    /** Ways a model says "this file has no pages" instead of leaving the line out. */
    private static boolean isNoPage(String page) {
        String value = page.strip().toLowerCase(Locale.ROOT);
        return value.equals("none") || value.equals("n/a") || value.equals("-");
    }

    /**
     * Rewrites the text's {@code [n]} markers to the citations' new numbers. A marker whose number
     * has no entry is a problem.
     */
    private static String renumberMarkers(
            String text, Map<Integer, Integer> newNumbers, List<String> problems) {
        Matcher marker = MARKER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (marker.find()) {
            List<String> renumbered = new ArrayList<>();
            for (String part : marker.group(1).split(",")) {
                int number = Integer.parseInt(truncate(part.strip()));
                Integer newNumber = newNumbers.get(number);
                if (newNumber == null) {
                    String problem =
                            "The answer uses ["
                                    + number
                                    + "] but there is no citation ["
                                    + number
                                    + "] under a "
                                    + CITATIONS_HEADER
                                    + " line.";
                    if (!problems.contains(problem)) {
                        problems.add(problem);
                    }
                    renumbered.add(part.strip());
                } else {
                    renumbered.add(newNumber.toString());
                }
            }
            marker.appendReplacement(
                    result, Matcher.quoteReplacement("[" + String.join(", ", renumbered) + "]"));
        }
        marker.appendTail(result);
        return result.toString();
    }

    /** Removes one pair of backticks or quotation marks around a value. */
    private static String unwrap(String value) {
        String text = value.strip();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            boolean pair =
                    (first == '"' && last == '"')
                            || (first == '`' && last == '`')
                            || (first == '“' && last == '”');
            if (pair) {
                return text.substring(1, text.length() - 1).strip();
            }
        }
        return text;
    }

    /** Keeps a run of digits short enough to parse as an int. */
    private static String truncate(String digits) {
        return digits.length() > 6 ? digits.substring(0, 6) : digits;
    }
}
