package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.search.SearchEngine;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchToolTest {

    static final Path ROOT = Path.of("data").toAbsolutePath();
    static final Path LEASE = ROOT.resolve("lease.pdf");
    static final Path SLIDES = ROOT.resolve("coop-info.pptx");
    static final Path NOTES = ROOT.resolve("notes.md");
    static final Path PHOTO = ROOT.resolve("lease-signing.jpg");

    @TempDir Path folder;
    SearchIndex index;
    SearchFolder searchFolder;
    ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        index = SearchIndex.open(folder);
        index.put(
                file(LEASE, LocalDate.of(2025, 8, 14), ExtractionStatus.OK),
                List.of(
                        new Chunk(
                                0, 1, "This lease is between the landlord and the tenant.", none()),
                        new Chunk(1, 2, "The lease ends on August 31, 2026.", none())));
        index.put(
                file(SLIDES, LocalDate.of(2024, 10, 2), ExtractionStatus.OK),
                List.of(new Chunk(0, 3, "Co-op info session: work terms and the lease.", none())));
        index.put(
                file(NOTES, LocalDate.of(2026, 3, 1), ExtractionStatus.OK),
                List.of(new Chunk(0, 0, "Remember to renew the lease in the spring.", none())));
        index.put(
                file(PHOTO, LocalDate.of(2025, 8, 15), ExtractionStatus.METADATA_ONLY), List.of());
        index.commit();
        registry = new ToolRegistry();
        searchFolder = new SearchFolder(ROOT, false, true, List.of());
        IndexConfig indexConfig = new IndexConfig(folder, List.of(searchFolder), List.of());
        registry.register(new SearchTool(new SearchEngine(index, indexConfig)));
    }

    @Test
    void findsNothingInAPausedFolder() {
        searchFolder.setPaused(true);
        ToolResult paused = search(Map.of("query", "lease ends"));
        assertThat(paused.succeeded()).isTrue();
        assertThat(paused.sourceFiles()).isEmpty();
        assertThat(paused.content()).startsWith("No files match \"lease ends\".");

        searchFolder.setPaused(false);
        assertThat(search(Map.of("query", "lease ends")).sourceFiles()).contains(LEASE);
    }

    @AfterEach
    void tearDown() throws Exception {
        index.close();
    }

    /** A file with content of its own: no other file has its hash. */
    static IndexedFile file(Path path, LocalDate modified, ExtractionStatus status) {
        return file(path, modified, status, hashOf(path));
    }

    static IndexedFile file(
            Path path, LocalDate modified, ExtractionStatus status, String contentHash) {
        Instant time = modified.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        return new IndexedFile(path, IndexedFile.fileTypeOf(path), 2048, time, contentHash, status);
    }

    static String hashOf(Path path) {
        return "hash of " + path;
    }

    static float[] none() {
        return new float[0];
    }

    /** Indexes a second file with the lease's content: the same hash and the same chunks. */
    Path indexCopyOfTheLease(LocalDate modified) throws Exception {
        Path copy = ROOT.resolve("backup").resolve("lease-copy.pdf");
        index.put(file(copy, modified, ExtractionStatus.OK, hashOf(LEASE)), index.getChunks(LEASE));
        index.commit();
        return copy;
    }

    /** A search by a request that hasn't been given any passage yet. */
    ToolResult search(Map<String, Object> arguments) {
        return search(arguments, new ShownPassages(file -> false));
    }

    ToolResult search(Map<String, Object> arguments, ShownPassages shownPassages) {
        return registry.execute(new ToolCall("call-1", SearchTool.NAME, arguments), shownPassages);
    }

    @Test
    void describesItselfToTheModel() {
        ToolSpec spec = registry.getToolSpecs().get(0);
        assertThat(spec.name()).isEqualTo("search");
        assertThat(spec.parameters())
                .extracting(ToolSpec.Parameter::name)
                .containsExactly(
                        "query", "file_type", "modified_after", "modified_before", "limit");
        assertThat(spec.parameters().get(0).required()).isTrue();
        assertThat(spec.parameters().get(1).allowedValues()).contains("pdf", "pptx");
    }

    @Test
    void returnsEachFileWithItsPathPageAndBestPassage() {
        ToolResult result = search(Map.of("query", "lease ends"));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.sourceFiles()).contains(LEASE, SLIDES, NOTES, PHOTO);
        assertThat(result.sourceFiles().get(0)).isEqualTo(LEASE);
        assertThat(result.content())
                .startsWith("4 files for \"lease ends\", best match first:")
                .contains("1. " + LEASE)
                .contains("pdf, 2048 bytes, modified 2025-08-14")
                .contains("Best passage (page 2):\nThe lease ends on August 31, 2026.")
                .contains("Best passage (slide 3):\nCo-op info session")
                .contains("Best passage:\nRemember to renew the lease")
                .contains(PHOTO + "\n   jpg, 2048 bytes, modified 2025-08-15")
                .contains("Matched by its name only; none of its text is indexed.")
                .doesNotContain("Same content at");
    }

    @Test
    void listsFilesWithTheSameContentAsOneEntryWithOnePassage() throws Exception {
        Path copy = indexCopyOfTheLease(LocalDate.of(2025, 9, 1));

        ToolResult result = search(Map.of("query", "ends august"));
        // Both files are sources. The better-ranked one is listed, with the other under it.
        assertThat(result.sourceFiles()).containsExactlyInAnyOrder(LEASE, copy);
        Path listed = result.sourceFiles().get(0);
        Path other = result.sourceFiles().get(1);
        assertThat(result.content())
                .startsWith("2 files for \"ends august\", best match first:\n\n1. " + listed + "\n")
                .endsWith(
                        "\n   Same content at: "
                                + other
                                + "\n   Best passage (page 2):\nThe lease ends on August 31, 2026.")
                .doesNotContain("\n2. ");
    }

    @Test
    void aCopyListedUnderAPassageCountsAsGivenIt() throws Exception {
        Path copy = indexCopyOfTheLease(LocalDate.of(2025, 9, 1));
        ShownPassages shownPassages = new ShownPassages(file -> false);
        search(Map.of("query", "ends august"), shownPassages);

        // Only the copy is new enough for this search, whichever of the two was listed before.
        ToolResult later =
                search(
                        Map.of("query", "ends august", "modified_after", "2025-09-01"),
                        shownPassages);
        assertThat(later.sourceFiles()).containsExactly(copy);
        assertThat(later.content())
                .endsWith(
                        "Best passage (page 2): already given above, in the results for \"ends"
                                + " august\".");
    }

    @Test
    void keepsSeparateEntriesForCopiesWithoutAPassageOrAContentHash() throws Exception {
        // A copy of the photo: the same hash, but no text, so there is no passage to share.
        Path photoCopy = ROOT.resolve("backup").resolve("lease-signing.jpg");
        LocalDate date = LocalDate.of(2025, 8, 15);
        index.put(file(photoCopy, date, ExtractionStatus.METADATA_ONLY, hashOf(PHOTO)), List.of());
        // Two files whose hashes aren't known can't be told to be copies.
        Path first = ROOT.resolve("passport-1.txt");
        Path second = ROOT.resolve("passport-2.txt");
        List<Chunk> chunks = List.of(new Chunk(0, 0, "Passport renewal form.", none()));
        index.put(file(first, date, ExtractionStatus.OK, ""), chunks);
        index.put(file(second, date, ExtractionStatus.OK, ""), chunks);
        index.commit();

        ToolResult photos = search(Map.of("query", "signing"));
        assertThat(photos.sourceFiles()).containsExactlyInAnyOrder(PHOTO, photoCopy);
        assertThat(photos.content()).contains("\n2. ").doesNotContain("Same content at");

        ToolResult passports = search(Map.of("query", "renewal form"));
        assertThat(passports.sourceFiles()).containsExactlyInAnyOrder(first, second);
        assertThat(passports.content()).contains("\n2. ").doesNotContain("Same content at");
    }

    @Test
    void givesOnlyThePartAroundTheQuerysWordsAfterTheFirstThreeEntries() throws Exception {
        LocalDate date = LocalDate.of(2025, 5, 1);
        for (int i = 1; i <= 3; i++) {
            Path strongMatch = ROOT.resolve("cv-" + i + ".txt");
            String text = "Résumé, résumé, résumé " + i + ".";
            index.put(
                    file(strongMatch, date, ExtractionStatus.OK),
                    List.of(new Chunk(0, 0, text, none())));
        }
        // A long passage with the query's word once, in the middle, so it ranks last.
        Path handout = ROOT.resolve("handout.pdf");
        String sentence = "Bring a printed résumé to the session.";
        String passage =
                "OPENING "
                        + "Filler words that say nothing. ".repeat(10)
                        + "\n"
                        + sentence
                        + " More filler words that say nothing.".repeat(10)
                        + " CLOSING";
        index.put(
                file(handout, date, ExtractionStatus.OK),
                List.of(new Chunk(0, 7, passage, none())));
        index.commit();
        ShownPassages shownPassages = new ShownPassages(file -> false);

        ToolResult result = search(Map.of("query", "resume"), shownPassages);
        assertThat(result.sourceFiles()).hasSize(4);
        assertThat(result.sourceFiles().get(3)).isEqualTo(handout);
        // The first three entries have their whole passage.
        assertThat(result.content()).contains("Best passage:\nRésumé, résumé, résumé 1.");
        // The fourth has one line of at most PART_CHARS characters, cut at both ends.
        String fourth = result.content().substring(result.content().indexOf("4. " + handout));
        assertThat(fourth)
                .contains("\n   Part of the best passage (page 7):\n…")
                .contains(sentence)
                .endsWith("…")
                .doesNotContain("OPENING")
                .doesNotContain("CLOSING");
        String part = fourth.substring(fourth.lastIndexOf('\n') + 1);
        assertThat(part.length()).isLessThanOrEqualTo(SearchTool.PART_CHARS + 2);

        // A part isn't the passage: a later search that ranks the file first gives all of it.
        ToolResult later = search(Map.of("query", "printed"), shownPassages);
        assertThat(later.sourceFiles()).containsExactly(handout);
        assertThat(later.content()).contains("Best passage (page 7):\nOPENING").endsWith("CLOSING");

        // And once it was given whole, a still later search names that one.
        ToolResult last = search(Map.of("query", "resume"), shownPassages);
        assertThat(last.content())
                .endsWith(
                        "Best passage (page 7): already given above, in the results for"
                                + " \"printed\".");
    }

    @Test
    void aLaterSearchOfTheRequestNamesTheEarlierSearchInPlaceOfAPassage() {
        ShownPassages shownPassages = new ShownPassages(file -> false);
        search(Map.of("query", "lease ends"), shownPassages);

        ToolResult later = search(Map.of("query", "ends august"), shownPassages);
        // The file is still listed and still a source; only the passage's text is left out.
        assertThat(later.sourceFiles()).containsExactly(LEASE);
        assertThat(later.content())
                .isEqualTo(
                        "1 file for \"ends august\", best match first:\n\n1. "
                                + LEASE
                                + "\n   pdf, 2048 bytes, modified 2025-08-14"
                                + "\n   Best passage (page 2): already given above, in the results"
                                + " for \"lease ends\".");

        ToolResult withoutPages = search(Map.of("query", "renew spring"), shownPassages);
        assertThat(withoutPages.sourceFiles()).containsExactly(NOTES);
        assertThat(withoutPages.content())
                .endsWith("Best passage: already given above, in the results for \"lease ends\".")
                .doesNotContain("Remember to renew");
    }

    @Test
    void anotherPassageOfAFileAlreadyGivenComesInFull() {
        ShownPassages shownPassages = new ShownPassages(file -> false);
        search(Map.of("query", "lease ends"), shownPassages);

        ToolResult later = search(Map.of("query", "landlord tenant"), shownPassages);
        assertThat(later.content())
                .contains(
                        "Best passage (page 1):\n"
                                + "This lease is between the landlord and the tenant.");
    }

    @Test
    void aNewRequestIsGivenThePassageAgain() {
        search(Map.of("query", "lease ends"), new ShownPassages(file -> false));

        ToolResult next = search(Map.of("query", "ends august"), new ShownPassages(file -> false));
        assertThat(next.content())
                .contains("Best passage (page 2):\nThe lease ends on August 31, 2026.");
    }

    @Test
    void aPassageOfAWithheldResultIsGivenAgain() {
        // As for a cloud model when the notes are in a local-only folder: the first result holds
        // the notes, so the model is sent none of it.
        ShownPassages shownPassages = new ShownPassages(NOTES::equals);
        ToolResult withheld = search(Map.of("query", "lease ends"), shownPassages);
        assertThat(withheld.sourceFiles()).contains(LEASE, NOTES);

        ToolResult later = search(Map.of("query", "ends august"), shownPassages);
        assertThat(later.sourceFiles()).containsExactly(LEASE);
        assertThat(later.content())
                .contains("Best passage (page 2):\nThe lease ends on August 31, 2026.");

        // That result held no withheld file, so the model has the passage now.
        ToolResult third = search(Map.of("query", "august 2026"), shownPassages);
        assertThat(third.content())
                .endsWith(
                        "Best passage (page 2): already given above, in the results for \"ends"
                                + " august\".");
    }

    @Test
    void appliesTheTypeAndDateFiltersAndTheLimit() {
        ToolResult slides = search(Map.of("query", "lease", "file_type", "pptx"));
        assertThat(slides.sourceFiles()).containsExactly(SLIDES);
        assertThat(slides.content()).startsWith("1 file for \"lease\"");

        ToolResult from2025 =
                search(
                        Map.of(
                                "query", "lease",
                                "modified_after", "2025-01-01",
                                "modified_before", "2025-12-31"));
        assertThat(from2025.sourceFiles()).containsExactlyInAnyOrder(LEASE, PHOTO);

        assertThat(search(Map.of("query", "lease", "limit", 2)).sourceFiles()).hasSize(2);
        assertThat(search(Map.of("query", "lease", "limit", 2.0)).sourceFiles()).hasSize(2);
    }

    @Test
    void listsTheNewestFilesForAnEmptyQuery() {
        ToolResult result = search(Map.of("query", " ", "limit", 2));
        assertThat(result.sourceFiles()).containsExactly(NOTES, PHOTO);
        assertThat(result.content())
                .startsWith("2 files, most recently modified first:")
                .doesNotContain("Best passage")
                .doesNotContain("Matched by its name only");
    }

    @Test
    void saysWhenNothingMatchesSoTheModelCanTryAgain() {
        ToolResult none = search(Map.of("query", "passport"));
        assertThat(none.succeeded()).isTrue();
        assertThat(none.sourceFiles()).isEmpty();
        assertThat(none.content())
                .isEqualTo("No files match \"passport\". Try fewer or different keywords.");

        ToolResult filtered = search(Map.of("query", "lease", "modified_after", "2030-01-01"));
        assertThat(filtered.content()).startsWith("No files match \"lease\" with these filters.");

        ToolResult empty = search(Map.of("query", "", "file_type", "docx"));
        assertThat(empty.content()).startsWith("No indexed files with these filters.");
    }

    @Test
    void rejectsAMalformedDateAnUnknownFileTypeAndABadLimit() {
        ToolResult date = search(Map.of("query", "lease", "modified_after", "last year"));
        assertThat(date.succeeded()).isFalse();
        assertThat(date.errorMessage())
                .startsWith("Invalid call to search: ")
                .contains(
                        "Parameter \"modified_after\" must be a date written as YYYY-MM-DD,"
                                + " such as 2025-01-31, but was \"last year\".");

        ToolResult range =
                search(
                        Map.of(
                                "query", "lease",
                                "modified_after", "2026-01-01",
                                "modified_before", "2025-01-01"));
        assertThat(range.errorMessage())
                .contains(
                        "\"modified_after\" (2026-01-01) is later than \"modified_before\""
                                + " (2025-01-01).");

        ToolResult type = search(Map.of("query", "lease", "file_type", "slides"));
        assertThat(type.errorMessage())
                .contains(
                        "Parameter \"file_type\" must be one of pdf, docx, pptx, txt, md, but"
                                + " was \"slides\".");

        assertThat(search(Map.of("query", "lease", "limit", 0)).errorMessage())
                .contains("Parameter \"limit\" must be from 1 to 20, but was 0.");
        assertThat(search(Map.of("query", "lease", "limit", 500)).errorMessage())
                .contains("Parameter \"limit\" must be from 1 to 20, but was 500.");
    }
}
