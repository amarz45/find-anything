package io.github.amarz45.findanything.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.IndexConfig;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchFiltersTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    @TempDir Path appData;

    static IndexedFile file(String relative, String type, long size, LocalDate day) {
        Instant modified = day.atStartOfDay(ZoneId.systemDefault()).toInstant().plusSeconds(3600);
        return new IndexedFile(
                CORPUS.resolve(relative), type, size, modified, "", ExtractionStatus.OK);
    }

    @Test
    void noFiltersMatchEverything() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        config.addFolder(CORPUS);
        assertThat(SearchFilters.NONE.isEmpty()).isTrue();
        assertThat(SearchFilters.NONE.validate(config)).isEmpty();
        assertThat(SearchFilters.NONE.matches(file("a.pdf", "pdf", 10, LocalDate.of(2026, 1, 1))))
                .isTrue();
    }

    @Test
    void eachFilterNarrowsTheMatch() {
        LocalDate march = LocalDate.of(2026, 3, 12);
        IndexedFile pdf = file("housing/lease.pdf", "pdf", 5_000, march);
        SearchFilters filters =
                new SearchFilters(
                        Set.of("pdf", "docx"),
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 31),
                        1_000,
                        10_000,
                        CORPUS.resolve("housing"));
        assertThat(filters.isEmpty()).isFalse();
        assertThat(filters.matches(pdf)).isTrue();
        assertThat(filters.matches(file("housing/lease.txt", "txt", 5_000, march))).isFalse();
        assertThat(filters.matches(file("housing/lease.pdf", "pdf", 500, march))).isFalse();
        assertThat(filters.matches(file("housing/lease.pdf", "pdf", 50_000, march))).isFalse();
        assertThat(
                        filters.matches(
                                file("housing/lease.pdf", "pdf", 5_000, LocalDate.of(2026, 4, 1))))
                .isFalse();
        assertThat(filters.matches(file("work/lease.pdf", "pdf", 5_000, march))).isFalse();
        assertThat(
                        filters.matches(
                                file("housing/lease.pdf", "pdf", 5_000, LocalDate.of(2026, 3, 31))))
                .isTrue();
    }

    @Test
    void validateReportsEveryProblem() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        config.addFolder(CORPUS);
        SearchFilters filters =
                new SearchFilters(
                        Set.of(),
                        LocalDate.of(2026, 12, 31),
                        LocalDate.of(2026, 1, 1),
                        100,
                        10,
                        Path.of("testdata"));
        assertThat(filters.validate(config))
                .hasSize(3)
                .anySatisfy(e -> assertThat(e).contains("start date"))
                .anySatisfy(e -> assertThat(e).contains("minimum size"))
                .anySatisfy(e -> assertThat(e).contains("outside the search folders"));
        assertThat(
                        new SearchFilters(Set.of(), null, null, -1, -1, CORPUS.resolve("notes"))
                                .validate(config))
                .isEmpty();
    }
}
