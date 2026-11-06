package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadExcerptToolTest {

    static final Path ROOT = Path.of("data").toAbsolutePath();
    static final Path LEASE = ROOT.resolve("lease.pdf");
    static final Path SLIDES = ROOT.resolve("coop-info.pptx");
    static final Path NOTES = ROOT.resolve("notes.md");
    static final Path SCAN = ROOT.resolve("scan.pdf");
    static final Path PHOTO = ROOT.resolve("photo.jpg");
    static final Path BROKEN = ROOT.resolve("broken.docx");
    static final Path LONG = ROOT.resolve("long.pdf");

    /** Room for two of the lease's chunks, but not three. */
    static final int MAX_EXCERPT_CHARS = 70;

    @TempDir Path folder;
    SearchIndex index;
    SearchFolder searchFolder;
    IndexConfig indexConfig;
    ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        index = SearchIndex.open(folder);
        index.put(
                file(LEASE, ExtractionStatus.OK),
                List.of(
                        chunk(0, 1, "This lease is between the landlord"),
                        chunk(1, 1, "and the tenant."),
                        chunk(2, 2, "The lease ends on August 31, 2026."),
                        chunk(3, 4, "Signed in Toronto.")));
        index.put(file(SLIDES, ExtractionStatus.OK), List.of(chunk(0, 3, "Work terms")));
        index.put(
                file(NOTES, ExtractionStatus.OK),
                List.of(chunk(0, 0, "Renew the lease."), chunk(1, 0, "Call the landlord.")));
        index.put(file(SCAN, ExtractionStatus.NO_TEXT), List.of());
        index.put(file(PHOTO, ExtractionStatus.METADATA_ONLY), List.of());
        index.put(file(BROKEN, ExtractionStatus.FAILED), List.of());
        index.put(file(LONG, ExtractionStatus.PARTIAL), List.of(chunk(0, 1, "x".repeat(100))));
        index.commit();
        registry = new ToolRegistry();
        searchFolder = new SearchFolder(ROOT, false, true, List.of());
        indexConfig = new IndexConfig(folder, List.of(searchFolder), List.of());
        registry.register(new ReadExcerptTool(index, indexConfig, MAX_EXCERPT_CHARS));
    }

    @Test
    void refusesAFileInAPausedFolder() {
        searchFolder.setPaused(true);
        ToolResult paused = read(Map.of("file", LEASE.toString()));
        assertThat(paused.succeeded()).isFalse();
        assertThat(paused.sourceFiles()).isEmpty();
        assertThat(paused.errorMessage())
                .isEqualTo(
                        LEASE
                                + " is in a search folder that is paused or unavailable, so it"
                                + " can't be read now.");

        searchFolder.setPaused(false);
        assertThat(read(Map.of("file", LEASE.toString())).succeeded()).isTrue();
    }

    @AfterEach
    void tearDown() throws Exception {
        index.close();
    }

    static IndexedFile file(Path path, ExtractionStatus status) {
        return new IndexedFile(
                path, IndexedFile.fileTypeOf(path), 100, Instant.ofEpochSecond(1), "h", status);
    }

    static Chunk chunk(int position, int pageNumber, String text) {
        return new Chunk(position, pageNumber, text, new float[0]);
    }

    ToolResult read(Map<String, Object> arguments) {
        return registry.execute(new ToolCall("call-1", ReadExcerptTool.NAME, arguments));
    }

    @Test
    void describesItselfToTheModel() {
        ToolSpec spec = registry.getToolSpecs().get(0);
        assertThat(spec.name()).isEqualTo("read_excerpt");
        assertThat(spec.parameters())
                .extracting(ToolSpec.Parameter::name)
                .containsExactly("file", "page", "position");
        assertThat(spec.parameters().get(0).required()).isTrue();
        assertThatThrownBy(() -> new ReadExcerptTool(index, indexConfig, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsFromTheStartMarkingPagesAndSaysHowToReadOn() {
        ToolResult result = read(Map.of("file", LEASE.toString()));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.sourceFiles()).containsExactly(LEASE);
        assertThat(result.content())
                .isEqualTo(
                        "Excerpt of "
                                + LEASE
                                + ":\n\n[page 1]\nThis lease is between the landlord\n"
                                + "and the tenant.\n\n"
                                + "[More of this file follows. To read on, call read_excerpt with"
                                + " the same file and position 2.]");
    }

    @Test
    void continuesFromAPositionToTheEndOfTheFile() {
        ToolResult result = read(Map.of("file", LEASE.toString(), "position", 2));
        assertThat(result.content())
                .isEqualTo(
                        "Excerpt of "
                                + LEASE
                                + ":\n\n[page 2]\nThe lease ends on August 31, 2026.\n\n"
                                + "[page 4]\nSigned in Toronto.\n\n[End of the file's text.]");
    }

    @Test
    void startsAtAPageOrSlide() {
        assertThat(read(Map.of("file", LEASE.toString(), "page", 2.0)).content())
                .contains(":\n\n[page 2]\nThe lease ends on August 31, 2026.")
                .doesNotContain("[page 1]");
        assertThat(read(Map.of("file", SLIDES.toString(), "page", 3)).content())
                .contains("[slide 3]\nWork terms\n\n[End of the file's text.]");
    }

    @Test
    void readsAFileWithoutPagesWithNoPageMarks() {
        assertThat(read(Map.of("file", NOTES.toString())).content())
                .isEqualTo(
                        "Excerpt of "
                                + NOTES
                                + ":\n\nRenew the lease.\nCall the landlord.\n\n"
                                + "[End of the file's text.]");
    }

    @Test
    void cutsAChunkLongerThanTheLimitAndSaysWhenOnlyPartIsIndexed() {
        ToolResult result = read(Map.of("file", LONG.toString()));
        assertThat(result.content())
                .contains("[page 1]\n" + "x".repeat(MAX_EXCERPT_CHARS) + "…\n\n")
                .doesNotContain("x".repeat(MAX_EXCERPT_CHARS + 1))
                .endsWith(
                        "[End of the indexed text. The file is longer, but only its first"
                                + " part is indexed.]");
    }

    @Test
    void reportsAFileThatIsNotIndexedOrHasNoText() {
        ToolResult missing = read(Map.of("file", ROOT.resolve("secret.txt").toString()));
        assertThat(missing.succeeded()).isFalse();
        assertThat(missing.errorMessage())
                .startsWith("No indexed file has the path " + ROOT.resolve("secret.txt"));

        assertThat(read(Map.of("file", SCAN.toString())).errorMessage())
                .isEqualTo(SCAN + " can't be read: it has no text layer (it may be a scan).");
        assertThat(read(Map.of("file", PHOTO.toString())).errorMessage())
                .contains("its format isn't supported, so it is indexed by name only");
        assertThat(read(Map.of("file", BROKEN.toString())).errorMessage())
                .contains("its text could not be extracted");
    }

    @Test
    void reportsAPageOrPositionTheFileDoesNotHave() {
        assertThat(read(Map.of("file", LEASE.toString(), "page", 3)).errorMessage())
                .isEqualTo(
                        LEASE
                                + " has no text on page 3; its pages with text are between 1"
                                + " and 4.");
        assertThat(read(Map.of("file", SLIDES.toString(), "page", 9)).errorMessage())
                .contains("has no text on slide 9; its slides with text are between 3 and 3.");
        assertThat(read(Map.of("file", NOTES.toString(), "page", 1)).errorMessage())
                .isEqualTo(NOTES + " has no page numbers; leave \"page\" out.");
        assertThat(read(Map.of("file", LEASE.toString(), "position", 4)).errorMessage())
                .isEqualTo(LEASE + " has no position 4; its positions are 0 to 3.");
    }

    @Test
    void rejectsABadPathAndConflictingOrNegativeStarts() {
        ToolResult relative = read(Map.of("file", "lease.pdf"));
        assertThat(relative.succeeded()).isFalse();
        assertThat(relative.errorMessage())
                .startsWith("Invalid call to read_excerpt: ")
                .contains("must be a full path as the search tool gave it, but was \"lease.pdf\"");

        assertThat(read(Map.of("file", "bad\0path")).errorMessage())
                .contains("Parameter \"file\" isn't a valid path");

        ToolResult both = read(Map.of("file", LEASE.toString(), "page", 0, "position", -1));
        assertThat(both.errorMessage())
                .contains("Give \"page\" or \"position\", not both.")
                .contains("Parameter \"page\" must be 1 or more, but was 0.")
                .contains("Parameter \"position\" must be 0 or more, but was -1.");
    }
}
