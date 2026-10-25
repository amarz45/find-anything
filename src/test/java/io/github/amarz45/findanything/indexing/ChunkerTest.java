package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.storage.Chunk;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    static final Path FILE = Path.of("doc.pdf");

    static String wordsOf(List<Chunk> chunks) {
        return chunks.stream()
                .map(Chunk::text)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ");
    }

    @Test
    void aShortPageIsOneChunk() {
        Chunker chunker = new Chunker(100);
        ExtractedDocument document =
                new ExtractedDocument("text/plain", List.of("Hello\nworld"), false, false);
        List<Chunk> chunks = chunker.split(FILE, document);
        assertThat(chunks).containsExactly(new Chunk(0, 0, "Hello\nworld", new float[0]));
    }

    @Test
    void pagesKeepTheirNumbersAndPositionsRunAcrossTheFile() {
        Chunker chunker = new Chunker(100);
        ExtractedDocument document =
                new ExtractedDocument(
                        "application/pdf", List.of("page one", "", "page three"), true, false);
        List<Chunk> chunks = chunker.split(FILE, document);
        assertThat(chunks).extracting(Chunk::position).containsExactly(0, 1);
        assertThat(chunks).extracting(Chunk::pageNumber).containsExactly(1, 3);
        assertThat(chunks).extracting(Chunk::text).containsExactly("page one", "page three");
    }

    @Test
    void linesAreGroupedUpToTheLimitWithoutLosingWords() {
        Chunker chunker = new Chunker(40);
        String page =
                IntStream.rangeClosed(1, 12)
                        .mapToObj(i -> "Line number " + i + " here.")
                        .collect(Collectors.joining("\n"));
        List<Chunk> chunks =
                chunker.split(
                        FILE, new ExtractedDocument("text/plain", List.of(page), false, false));
        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(40));
        assertThat(chunks)
                .allSatisfy(c -> assertThat(c.text()).startsWith("Line").endsWith("here."));
        assertThat(wordsOf(chunks)).isEqualTo(page.replace("\n", " "));
    }

    @Test
    void aLongLineIsSplitAtSentenceEndsThenWords() {
        Chunker chunker = new Chunker(60);
        String line =
                "The first sentence is short. The second sentence is a little bit longer than that."
                        + " Third? Yes! "
                        + "Then a very long run of words without any punctuation at all that goes on and on and on";
        List<Chunk> chunks =
                chunker.split(
                        FILE, new ExtractedDocument("text/plain", List.of(line), false, false));
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(60));
        assertThat(chunks.get(0).text()).isEqualTo("The first sentence is short.");
        assertThat(chunks.get(1).text())
                .isEqualTo("The second sentence is a little bit longer than that. Third?");
        assertThat(chunks)
                .allSatisfy(c -> assertThat(c.text()).doesNotStartWith(" ").doesNotEndWith(" "));
        assertThat(wordsOf(chunks)).isEqualTo(line.replaceAll("\\s+", " ").strip());
        // every boundary falls between whole words
        for (Chunk chunk : chunks) {
            assertThat(line).contains(chunk.text());
        }
    }

    @Test
    void anOversizedWordIsCutRatherThanLoopingForever() {
        Chunker chunker = new Chunker(20);
        String word = "x".repeat(55);
        List<Chunk> chunks =
                chunker.split(
                        FILE,
                        new ExtractedDocument(
                                "text/plain", List.of("ok " + word + " end"), false, false));
        assertThat(chunks)
                .extracting(Chunk::text)
                .containsExactly("ok", "x".repeat(20), "x".repeat(20), "x".repeat(15) + " end");
    }

    @Test
    void blankPagesProduceNoChunks() {
        Chunker chunker = new Chunker(100);
        assertThat(
                        chunker.split(
                                FILE,
                                new ExtractedDocument(
                                        "application/pdf", List.of("", " \n "), true, false)))
                .isEmpty();
        assertThatThrownBy(() -> new Chunker(5)).isInstanceOf(IllegalArgumentException.class);
    }
}
