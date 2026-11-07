package io.github.amarz45.findanything.ui;

import static io.github.amarz45.findanything.ui.FxTestSupport.awaitFx;
import static io.github.amarz45.findanything.ui.FxTestSupport.onFxThread;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.ScriptedLlmClient;
import io.github.amarz45.findanything.llm.ScriptedLlmClientFactory;
import io.github.amarz45.findanything.storage.IndexMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real views through the controller, with the real facade on a scratch home. */
class GuiControllerTest {

    @TempDir Path home;
    Path docs;
    FindAnythingService service;
    MainWindow window;
    GuiController controller;
    Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("dentist.txt"), "Dentist appointment on March 4, 2026.");
        Files.writeString(
                docs.resolve("lecture.pdf"), "Slide one.\fMerge sort runs in O(n log n) time.");
        Files.writeString(docs.resolve("photo.bin"), "binary");
        Files.writeString(docs.resolve("corrupt.txt"), "bad");
        service = newService();
        assertThat(service.open(false)).isEqualTo(IndexMode.WRITER);
        onFxThread(
                () -> {
                    window = new MainWindow();
                    controller = new GuiController(service, window);
                    stage = new Stage();
                    window.show(stage);
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxThread(() -> stage.hide());
        service.close();
    }

    FindAnythingService newService() throws Exception {
        return CliAppTest.service(
                home.resolve("app"),
                new ScriptedLlmClientFactory(
                        new ScriptedLlmClient(new LlmResponse("OK", List.of()))));
    }

    // ------------------------------------------------------------------ helpers

    Set<String> shownFiles(MainWindow window) throws Exception {
        return onFxThread(
                () ->
                        window.getResultsView().getItems().stream()
                                .map(result -> result.file().path().getFileName().toString())
                                .collect(Collectors.toSet()));
    }

    void awaitFiles(String... names) throws Exception {
        Set<String> expected = Set.of(names);
        awaitFx(
                () -> {
                    try {
                        return shownFilesNow(window).equals(expected);
                    } catch (RuntimeException e) {
                        return false;
                    }
                },
                () -> "results " + expected + ", shown " + shownFilesNow(window));
    }

    private static Set<String> shownFilesNow(MainWindow window) {
        return window.getResultsView().getItems().stream()
                .map(result -> result.file().path().getFileName().toString())
                .collect(Collectors.toSet());
    }

    String status(MainWindow window) throws Exception {
        return onFxThread(() -> statusNow(window));
    }

    /** For conditions that already run on the JavaFX thread, and for failure messages. */
    private static String statusNow(MainWindow window) {
        return ((Label) window.lookup(".status-bar")).getText();
    }

    List<String> chips(MainWindow window) throws Exception {
        return onFxThread(
                () ->
                        window.lookupAll(".chip").stream()
                                .map(node -> ((MenuButton) node).getText())
                                .toList());
    }

    String error(MainWindow window) throws Exception {
        return onFxThread(
                () -> {
                    Node bar = window.lookup(".error-bar");
                    return bar.isVisible() ? ((Label) bar.lookup(".label")).getText() : null;
                });
    }

    TextField input() {
        return (TextField) window.lookup(".query-input");
    }

    @Test
    void listsEveryFileThenFiltersAsTheUserTypes() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        assertThat(chips(window)).containsExactly("docs");
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");
        awaitFx(
                () -> statusNow(window).startsWith("Up to date"),
                () -> "status " + statusNow(window));
        assertThat(status(window)).isEqualTo("Up to date · 1 file could not be read");

        onFxThread(() -> input().setText("merge"));
        awaitFiles("lecture.pdf");
        onFxThread(() -> input().setText("nothing-like-this"));
        awaitFiles();
        onFxThread(() -> input().clear());
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");
    }

    @Test
    void selectingAResultShowsItsPreviewWithTheQueryHighlighted() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");
        onFxThread(() -> input().setText("merge sort"));
        awaitFiles("lecture.pdf");

        onFxThread(() -> window.getResultsView().getSelectionModel().select(0));
        awaitFx(
                () -> {
                    Node header = window.getDetailPane().lookup(".detail-header");
                    return header != null && "lecture.pdf".equals(((Label) header).getText());
                },
                () -> "the preview of lecture.pdf");
        List<String> highlights =
                onFxThread(
                        () ->
                                window.getDetailPane().lookupAll(".highlight").stream()
                                        .map(node -> ((Text) node).getText())
                                        .toList());
        assertThat(highlights).containsExactly("Merge sort");
    }

    @Test
    void folderProblemsAppearInTheErrorBar() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        Path inside = docs.resolve("inside");
        Files.createDirectories(inside);
        onFxThread(() -> controller.onAddFolder(inside));
        assertThat(error(window)).isNotBlank();
        assertThat(chips(window)).containsExactly("docs");

        onFxThread(() -> controller.onAddFolder(home.resolve("missing")));
        assertThat(error(window)).startsWith("No such file or folder");

        onFxThread(() -> ((javafx.scene.control.Button) window.lookup(".dismiss-error")).fire());
        assertThat(error(window)).isNull();
    }

    @Test
    void removingAFolderClearsItsChipAndItsResults() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");

        onFxThread(() -> controller.onRemoveFolder(docs));
        assertThat(chips(window)).isEmpty();
        awaitFiles();
        assertThat(error(window)).isNull();
    }

    @Test
    void aReadOnlyProcessShowsItsStatusAndRefusesFolderChanges() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");

        FindAnythingService second = newService();
        try {
            assertThat(second.open(false)).isEqualTo(IndexMode.READ_ONLY);
            MainWindow[] other = new MainWindow[1];
            GuiController[] otherController = new GuiController[1];
            Stage[] otherStage = new Stage[1];
            onFxThread(
                    () -> {
                        other[0] = new MainWindow();
                        otherController[0] = new GuiController(second, other[0]);
                        otherStage[0] = new Stage();
                        other[0].show(otherStage[0]);
                    });
            try {
                awaitFx(
                        () -> statusNow(other[0]).startsWith(MainWindow.READ_ONLY_STATUS),
                        () -> "read-only status, got " + statusNow(other[0]));
                assertThat(chips(other[0])).containsExactly("docs");
                assertThat(onFxThread(() -> other[0].lookup(".add-folder").isDisable())).isTrue();
                awaitFx(
                        () -> shownFilesNow(other[0]).size() == 4,
                        () -> "the read-only process lists the files");

                Path more = home.resolve("more");
                Files.createDirectories(more);
                onFxThread(() -> otherController[0].onAddFolder(more));
                assertThat(error(other[0])).contains("read-only");
                assertThat(chips(other[0])).containsExactly("docs");
            } finally {
                onFxThread(() -> otherStage[0].hide());
            }
        } finally {
            second.close();
        }
    }
}
