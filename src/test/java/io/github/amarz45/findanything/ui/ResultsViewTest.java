package io.github.amarz45.findanything.ui;

import static io.github.amarz45.findanything.ui.FxTestSupport.onFxThread;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TableCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultsViewTest {

    static final Instant MODIFIED = Instant.parse("2026-03-04T15:30:00Z");

    ResultsView view;
    Stage stage;
    List<SearchResult> selected = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        onFxThread(
                () -> {
                    view = new ResultsView();
                    view.setOnResultSelected(selected::add);
                    stage = new Stage();
                    stage.setScene(new Scene(view, 900, 400));
                    stage.show();
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxThread(() -> stage.hide());
    }

    static IndexedFile file(String name, ExtractionStatus status) {
        return new IndexedFile(
                Path.of("/docs", name).toAbsolutePath(),
                IndexedFile.fileTypeOf(Path.of(name)),
                2_048,
                MODIFIED,
                "hash",
                status);
    }

    static SearchResult result(String name, ExtractionStatus status, Chunk bestChunk) {
        return new SearchResult(file(name, status), bestChunk, 1.0);
    }

    /** The text of every rendered cell, row by row. */
    List<String> cellTexts() throws Exception {
        return onFxThread(
                () -> {
                    view.applyCss();
                    view.layout();
                    return view.lookupAll(".table-cell").stream()
                            .map(node -> ((TableCell<?, ?>) node).getText())
                            .filter(text -> text != null && !text.isEmpty())
                            .toList();
                });
    }

    @Test
    void showsEachFileWithItsPassageAndMarksExtractionProblems() throws Exception {
        SearchResult lecture =
                result(
                        "lecture.pdf",
                        ExtractionStatus.OK,
                        new Chunk(1, 2, "Merge sort runs in O(n log n) time.", new float[0]));
        SearchResult corrupt = result("corrupt.txt", ExtractionStatus.FAILED, null);
        onFxThread(() -> view.setResults(List.of(lecture, corrupt)));

        assertThat(cellTexts())
                .contains(
                        "lecture.pdf",
                        "page 2: Merge sort runs in O(n log n) time.",
                        "⚠ corrupt.txt",
                        "2.0 KB");
        assertThat(view.getItems()).containsExactly(lecture, corrupt);
    }

    @Test
    void selectingARowTellsTheControllerOnce() throws Exception {
        SearchResult first = result("a.txt", ExtractionStatus.OK, null);
        SearchResult second = result("b.txt", ExtractionStatus.OK, null);
        onFxThread(() -> view.setResults(List.of(first, second)));

        onFxThread(() -> view.getSelectionModel().select(first));
        assertThat(selected).containsExactly(first);
        assertThat(view.getSelectedResults()).containsExactly(first);

        // New results that still hold the selected file keep it selected, quietly.
        onFxThread(() -> view.setResults(List.of(second, first)));
        assertThat(onFxThread(() -> view.getSelectionModel().getSelectedItem())).isEqualTo(first);
        assertThat(selected).containsExactly(first);

        // Results without it clear the selection.
        onFxThread(() -> view.setResults(List.of(second)));
        assertThat(onFxThread(() -> view.getSelectionModel().getSelectedItem())).isNull();
        assertThat(selected).containsExactly(first);
    }

    @Test
    void noResultsSuggestsAskMode() throws Exception {
        List<String> clicks = new ArrayList<>();
        view.setOnTryAsk(() -> clicks.add("ask"));
        onFxThread(() -> view.setResults(List.of()));
        cellTexts();

        Hyperlink link = onFxThread(() -> (Hyperlink) view.lookup(".hyperlink"));
        assertThat(link).isNotNull();
        assertThat(link.getText()).isEqualTo("Try Ask mode");
        onFxThread(link::fire);
        assertThat(clicks).containsExactly("ask");
    }

    @Test
    void textHelpers() {
        assertThat(ResultsView.snippetOf(result("notes.txt", ExtractionStatus.OK, null))).isEmpty();
        assertThat(
                        ResultsView.snippetOf(
                                result(
                                        "deck.pptx",
                                        ExtractionStatus.OK,
                                        new Chunk(0, 3, "Revenue  grew\n12%", new float[0]))))
                .isEqualTo("slide 3: Revenue grew 12%");
        assertThat(ResultsView.nameText(file("plain.md", ExtractionStatus.OK)))
                .isEqualTo("plain.md");
        assertThat(ResultsView.nameText(file("scan.pdf", ExtractionStatus.NO_TEXT)))
                .isEqualTo("⚠ scan.pdf");
        assertThat(ResultsView.describeExtraction(ExtractionStatus.OK)).isEmpty();
        assertThat(ResultsView.describeExtraction(ExtractionStatus.METADATA_ONLY))
                .contains("name only");
    }
}
