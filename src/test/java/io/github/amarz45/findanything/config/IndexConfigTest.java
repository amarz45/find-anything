package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexConfigTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    @TempDir Path appData;

    @Test
    void builtInRulesKeepCredentialsAndLockFilesOut() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        config.addFolder(CORPUS);
        assertThat(config.isIncluded(CORPUS.resolve("home-like/.ssh/id_ed25519"))).isFalse();
        assertThat(config.isIncluded(CORPUS.resolve("school/~$assignment-2-report.docx")))
                .isFalse();
        assertThat(config.isIncluded(CORPUS.resolve("school/assignment-2-report.docx"))).isTrue();
        assertThat(config.isIncluded(CORPUS.resolve("housing/lease-2025-2026.pdf"))).isTrue();
    }

    @Test
    void filesOutsideTheSearchFoldersOrInTheAppDataFolderAreNotIncluded() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        config.addFolder(CORPUS);
        assertThat(config.isIncluded(Path.of("testdata", "FACTS.md"))).isFalse();
        assertThat(config.isIncluded(appData.resolve("index/segments_1"))).isFalse();
    }

    @Test
    void globalAndPerFolderRulesApply() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        SearchFolder folder = config.addFolder(CORPUS);
        config.addGlobalRule(ExclusionRule.parse("*.csv"));
        folder.addRule(ExclusionRule.parse("notes"));
        assertThat(config.isIncluded(CORPUS.resolve("edge/data.csv"))).isFalse();
        assertThat(config.isIncluded(CORPUS.resolve("notes/dentist.txt"))).isFalse();
        assertThat(config.isIncluded(CORPUS.resolve("work/project-budget-2026.txt"))).isTrue();
        assertThat(config.removeGlobalRule(ExclusionRule.parse("*.csv"))).isTrue();
        assertThat(config.isIncluded(CORPUS.resolve("edge/data.csv"))).isTrue();
    }

    @Test
    void rejectsFoldersInsideOrAroundAnExistingSearchFolder() throws Exception {
        IndexConfig config = new IndexConfig(appData);
        config.addFolder(CORPUS.resolve("school"));
        assertThatThrownBy(() -> config.addFolder(CORPUS.resolve("school")))
                .isInstanceOf(AlreadyCoveredException.class)
                .hasMessageContaining("already covered");
        assertThatThrownBy(() -> config.addFolder(CORPUS))
                .isInstanceOf(AlreadyCoveredException.class)
                .hasMessageContaining("contains the search folder");
        config.addFolder(CORPUS.resolve("work"));
        assertThat(config.getSearchFolders()).hasSize(2);
    }

    @Test
    void rejectsMissingPathsAndFiles() {
        IndexConfig config = new IndexConfig(appData);
        assertThatThrownBy(() -> config.addFolder(CORPUS.resolve("nope")))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> config.addFolder(CORPUS.resolve("edge/empty.txt")))
                .isInstanceOf(NotDirectoryException.class);
    }

    @Test
    void pausedAndUnavailableFoldersAreNotActiveButStayIncluded() throws Exception {
        Path gone = appData.resolveSibling("gone-" + System.nanoTime());
        IndexConfig config =
                new IndexConfig(
                        appData,
                        List.of(new SearchFolder(gone, false, false, List.of())),
                        List.of());
        SearchFolder folder = config.addFolder(CORPUS);
        folder.setPaused(true);
        assertThat(config.getActiveFolders()).isEmpty();
        assertThat(config.isIncluded(CORPUS.resolve("notes/dentist.txt"))).isTrue();
        folder.setPaused(false);
        assertThat(config.getActiveFolders()).containsExactly(folder);
        config.removeFolder(CORPUS);
        assertThat(config.findFolder(CORPUS)).isEmpty();
        assertThatThrownBy(() -> config.removeFolder(CORPUS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(gone)).isFalse();
    }
}
