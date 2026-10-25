package io.github.amarz45.findanything.indexing;

import io.github.amarz45.findanything.storage.Chunk;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits extracted text into consecutive chunks that together cover the whole file (F02). Each page
 * is split separately, so every chunk keeps its page or slide number. A chunk holds whole lines
 * when they fit; a line that doesn't fit is split at sentence ends, then at word boundaries. Only a
 * single word longer than the limit is ever cut in the middle.
 */
public final class Chunker {

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…][\"'”’)\\]]*\\s+");

    private final int maxChunkChars;

    /**
     * @param maxChunkChars the most characters a chunk may hold; at least 20
     */
    public Chunker(int maxChunkChars) {
        if (maxChunkChars < 20) {
            throw new IllegalArgumentException("maxChunkChars must be at least 20");
        }
        this.maxChunkChars = maxChunkChars;
    }

    /**
     * Splits a document's pages into chunks numbered from 0 across the whole file.
     *
     * @param file the file the document came from (kept with the chunks' origin for messages)
     * @param document the extracted text
     * @return the chunks in reading order; empty when the document has no text
     */
    public List<Chunk> split(Path file, ExtractedDocument document) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(document, "document");
        List<Chunk> chunks = new ArrayList<>();
        List<String> pages = document.pages();
        for (int i = 0; i < pages.size(); i++) {
            int pageNumber = document.numberedPages() ? i + 1 : 0;
            for (String text : splitPage(pages.get(i))) {
                chunks.add(new Chunk(chunks.size(), pageNumber, text, new float[0]));
            }
        }
        return chunks;
    }

    /** Splits one page's text into pieces of at most {@code maxChunkChars} characters. */
    List<String> splitPage(String page) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : page.strip().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String part : splitLine(trimmed)) {
                if (current.length() > 0 && current.length() + 1 + part.length() > maxChunkChars) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** A line that fits is returned whole; otherwise it is split at sentence ends, then words. */
    private List<String> splitLine(String line) {
        if (line.length() <= maxChunkChars) {
            return List.of(line);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences(line)) {
            for (String piece : words(sentence)) {
                if (current.length() > 0 && current.length() + 1 + piece.length() > maxChunkChars) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<String> sentences(String line) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_END.matcher(line);
        int start = 0;
        while (matcher.find()) {
            sentences.add(line.substring(start, matcher.end()).strip());
            start = matcher.end();
        }
        if (start < line.length()) {
            sentences.add(line.substring(start).strip());
        }
        return sentences;
    }

    /** A sentence that fits stays whole; a longer one becomes runs of words that fit. */
    private List<String> words(String sentence) {
        if (sentence.length() <= maxChunkChars) {
            return List.of(sentence);
        }
        List<String> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            while (word.length() > maxChunkChars) { // a single oversized word: cut it
                if (current.length() > 0) {
                    runs.add(current.toString());
                    current.setLength(0);
                }
                runs.add(word.substring(0, maxChunkChars));
                word = word.substring(maxChunkChars);
            }
            if (current.length() > 0 && current.length() + 1 + word.length() > maxChunkChars) {
                runs.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            runs.add(current.toString());
        }
        return runs;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }
}
