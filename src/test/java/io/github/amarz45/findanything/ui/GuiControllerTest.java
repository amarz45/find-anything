package io.github.amarz45.findanything.ui;

import static io.github.amarz45.findanything.ui.FxTestSupport.awaitFx;
import static io.github.amarz45.findanything.ui.FxTestSupport.onFxThread;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ScriptedLlmClient;
import io.github.amarz45.findanything.llm.ScriptedLlmClientFactory;
import io.github.amarz45.findanything.storage.IndexMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
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

    /** Replaces the service and the window with ones whose model comes from this factory. */
    void useModel(ScriptedLlmClientFactory factory) throws Exception {
        onFxThread(() -> stage.hide());
        service.close();
        service = CliAppTest.service(home.resolve("app"), factory);
        assertThat(service.open(false)).isEqualTo(IndexMode.WRITER);
        onFxThread(
                () -> {
                    window = new MainWindow();
                    controller = new GuiController(service, window);
                    stage = new Stage();
                    window.show(stage);
                });
    }

    /** The texts of the detail pane's nodes matching the selector. */
    List<String> detailTexts(String selector) throws Exception {
        return onFxThread(
                () -> {
                    window.applyCss();
                    window.layout();
                    return window.getDetailPane().lookupAll(selector).stream()
                            .map(DetailPaneTest::textOf)
                            .filter(text -> text != null)
                            .toList();
                });
    }

    void awaitDetail(String selector) throws Exception {
        awaitFx(
                () -> window.getDetailPane().lookup(selector) != null,
                () -> "a node matching " + selector + " in the detail pane");
    }

    static ScriptedLlmClient mergeSortScript(Path lecture, Path dentist) {
        return new ScriptedLlmClient(
                CliAppTest.calls("search", Map.of("query", "merge sort")),
                CliAppTest.says(
                        "Merge sort runs in O(n log n) time [1]. Your dentist appointment is on"
                                + " March 11 [2].\n\nCITATIONS:\n[1] file: "
                                + lecture
                                + "\npage: 2\nquote: Merge sort runs in O(n log n) time.\n"
                                + "[2] file: "
                                + dentist
                                + "\nquote: Dentist appointment on March 11, 2026."));
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

    @Test
    void askShowsTheAnswerWithItsCitationsAndOpensACitedFile() throws Exception {
        Path lecture = docs.resolve("lecture.pdf");
        Path dentist = docs.resolve("dentist.txt");
        useModel(new ScriptedLlmClientFactory(mergeSortScript(lecture, dentist)));
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");

        onFxThread(() -> controller.onAskSubmitted("How fast is merge sort?"));
        awaitDetail(".answer");
        assertThat(window.getMode()).isEqualTo(SearchMode.ASK);
        assertThat(detailTexts(".request")).containsExactly("How fast is merge sort?");
        assertThat(detailTexts(".progress")).isEmpty();
        assertThat(detailTexts(".citation-verified")).containsExactly("[1 ✓]");
        assertThat(detailTexts(".citation-unverified")).containsExactly("[2 ?]");
        assertThat(detailTexts(".source .hyperlink"))
                .containsExactly("[1] lecture.pdf, page 2", "[2] dentist.txt");
        assertThat(shownFiles(window)).contains("lecture.pdf");
        assertThat(onFxThread(() -> input().isDisable())).isFalse();

        // A citation opens the preview at the quote; Back returns to the conversation.
        onFxThread(() -> ((Hyperlink) window.getDetailPane().lookup(".citation-verified")).fire());
        awaitFx(
                () -> {
                    Node header = window.getDetailPane().lookup(".detail-header");
                    return header != null && "lecture.pdf".equals(((Label) header).getText());
                },
                () -> "the preview of lecture.pdf");
        assertThat(detailTexts(".highlight"))
                .containsExactly("Merge sort runs in O(n log n) time.");
        onFxThread(() -> ((Button) window.getDetailPane().lookup(".back-button")).fire());
        assertThat(onFxThread(() -> window.getDetailPane().lookup(".request").isVisible()))
                .isTrue();

        // New conversation empties the pane and the table.
        onFxThread(() -> controller.onNewConversation());
        assertThat(detailTexts(".request")).isEmpty();
        assertThat(detailTexts(".answer")).isEmpty();
        assertThat(detailTexts(".conversation-empty")).containsExactly(DetailPane.ASK_A_QUESTION);
        assertThat(shownFiles(window)).isEmpty();
    }

    @Test
    void askFallsBackToInstantSearchWhenTheModelIsUnavailable() throws Exception {
        useModel(new ScriptedLlmClientFactory(new LlmException("No OpenRouter API key.")));
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");

        onFxThread(() -> controller.onAskSubmitted("merge sort"));
        awaitDetail(".notice");
        assertThat(detailTexts(".notice").get(0))
                .startsWith("Note: " + FindAnythingService.MODEL_UNAVAILABLE)
                .contains("No OpenRouter API key.");
        assertThat(detailTexts(".citation-verified")).isEmpty();
        assertThat(shownFiles(window)).containsExactly("lecture.pdf");
        assertThat(onFxThread(() -> input().isDisable())).isFalse();
    }

    @Test
    void aSavedConversationIsListedReopenedAndContinued() throws Exception {
        Path lecture = docs.resolve("lecture.pdf");
        Path dentist = docs.resolve("dentist.txt");
        useModel(new ScriptedLlmClientFactory(mergeSortScript(lecture, dentist)));
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");
        onFxThread(() -> controller.onAskSubmitted("How fast is merge sort?"));
        awaitDetail(".answer");
        String id = service.listConversations().get(0).getId();

        // A fresh window, as after a restart: the menu lists the conversation and reopens it.
        useModel(new ScriptedLlmClientFactory(mergeSortScript(lecture, dentist)));
        List<MenuItem> items =
                onFxThread(() -> ((MenuButton) window.lookup(".conversations")).getItems());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getText()).endsWith("  How fast is merge sort?");
        onFxThread(() -> items.get(0).fire());
        assertThat(window.getMode()).isEqualTo(SearchMode.ASK);
        assertThat(detailTexts(".request")).containsExactly("How fast is merge sort?");
        assertThat(detailTexts(".citation-neutral")).containsExactly("[1]", "[2]");
        assertThat(detailTexts(".citation-verified")).isEmpty();
        assertThat(detailTexts(".source .detail-meta"))
                .containsExactly(DetailPane.NOT_REVERIFIED, DetailPane.NOT_REVERIFIED);
        assertThat(shownFiles(window)).contains("lecture.pdf");

        // The next request continues the reopened conversation.
        onFxThread(() -> controller.onAskSubmitted("And when is the dentist?"));
        awaitFx(
                () -> window.getDetailPane().lookupAll(".answer").size() == 2,
                () -> "the second answer");
        assertThat(detailTexts(".request"))
                .containsExactly("How fast is merge sort?", "And when is the dentist?");
        assertThat(
                        service.openConversation(id).getMessages().stream()
                                .filter(message -> message.role() == Message.Role.USER)
                                .map(Message::content))
                .containsExactly("How fast is merge sort?", "And when is the dentist?");
        assertThat(service.listConversations()).hasSize(1);
    }

    @Test
    void aChipMarksItsFolderLocalModelOnlyAndShowsTheMark() throws Exception {
        onFxThread(() -> controller.onAddFolder(docs));
        awaitFiles("dentist.txt", "lecture.pdf", "photo.bin", "corrupt.txt");
        assertThat(chips(window)).containsExactly("docs");

        CheckMenuItem item = localOnlyItem(window);
        assertThat(item.isSelected()).isFalse();
        onFxThread(
                () -> {
                    item.setSelected(true);
                    item.fire();
                });
        assertThat(chips(window)).containsExactly("docs (local model only)");
        assertThat(localOnlyItem(window).isSelected()).isTrue();
        assertThat(service.getLocalOnlyFolders()).containsExactly(docs);

        onFxThread(() -> controller.onSetLocalOnly(docs, false));
        assertThat(chips(window)).containsExactly("docs");
        assertThat(service.getLocalOnlyFolders()).isEmpty();

        // A folder marked inside it from the CLI shows on the chip too.
        service.setLocalOnly(docs.resolve("private"), true);
        onFxThread(() -> controller.onAddFolder(home.resolve("missing"))); // any refresh
        assertThat(chips(window)).containsExactly("docs (local model only inside)");
        assertThat(error(window)).startsWith("No such file or folder");
    }

    @Test
    void aReadOnlyProcessCannotChangeTheLocalOnlyMark() throws Exception {
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
                        () -> "read-only status");
                assertThat(onFxThread(() -> localOnlyItemNow(other[0]).isDisable())).isTrue();
                onFxThread(() -> otherController[0].onSetLocalOnly(docs, true));
                assertThat(error(other[0])).contains("read-only");
                assertThat(second.getLocalOnlyFolders()).isEmpty();
            } finally {
                onFxThread(() -> otherStage[0].hide());
            }
        } finally {
            second.close();
        }
    }

    /** The first chip's "Local model only" menu item, read on the JavaFX thread. */
    static CheckMenuItem localOnlyItem(MainWindow window) throws Exception {
        return onFxThread(() -> localOnlyItemNow(window));
    }

    /** As {@link #localOnlyItem}, for code that already runs on the JavaFX thread. */
    static CheckMenuItem localOnlyItemNow(MainWindow window) {
        return (CheckMenuItem)
                ((MenuButton) window.lookup(".chip"))
                        .getItems().stream()
                                .filter(item -> item instanceof CheckMenuItem)
                                .findFirst()
                                .orElseThrow();
    }

    // ------------------------------------------------------------------ the settings dialog

    <T extends Node> T inDialog(String selector) throws Exception {
        return onFxThread(
                () -> {
                    @SuppressWarnings("unchecked")
                    T node = (T) window.getSettingsDialog().getDialogPane().lookup(selector);
                    return node;
                });
    }

    String testResult() throws Exception {
        Label label = inDialog(".test-result");
        return onFxThread(label::getText);
    }

    @Test
    void settingsDialogTestsTheBackendAndSavesItWhenItAnswers() throws Exception {
        // Shown as the settings are now: the cloud backend from the environment, with its key.
        ChoiceBox<BackendType> backend = inDialog(".backend-choice");
        Label resultLabel = inDialog(".test-result");
        TextField modelName = inDialog(".model-name");
        Label keyLine = inDialog(".detail-meta");
        assertThat(onFxThread(backend::getValue)).isEqualTo(BackendType.CLOUD);
        assertThat(onFxThread(() -> modelName.getText())).isEqualTo("vendor/model");
        assertThat(onFxThread(keyLine::getText)).isEqualTo(SettingsDialog.KEY_SET);

        onFxThread(
                () -> {
                    backend.setValue(BackendType.LOCAL);
                    modelName.setText("llama");
                    ((Button) window.getSettingsDialog().getDialogPane().lookup(".test-connection"))
                            .fire();
                });
        assertThat(onFxThread(keyLine::isVisible)).isFalse();
        awaitFx(
                () -> !resultLabel.getText().equals(SettingsDialog.TESTING),
                () -> "the connection test's result");
        assertThat(testResult()).startsWith("Connected.").contains("local model llama");
        assertThat(service.getModelSettings().backendType()).isEqualTo(BackendType.LOCAL);
        assertThat(service.getModelSettings().modelName()).isEqualTo("llama");
    }

    @Test
    void aFailedConnectionTestSavesNothing() throws Exception {
        useModel(
                new ScriptedLlmClientFactory(
                        new ScriptedLlmClient(
                                new LlmException("OpenRouter rejected the API key."))));
        TextField modelName = inDialog(".model-name");
        Label resultLabel = inDialog(".test-result");
        onFxThread(
                () -> {
                    modelName.setText("vendor/other");
                    ((Button) window.getSettingsDialog().getDialogPane().lookup(".test-connection"))
                            .fire();
                });
        awaitFx(
                () -> !resultLabel.getText().equals(SettingsDialog.TESTING),
                () -> "the connection test's result");
        assertThat(testResult()).isEqualTo("OpenRouter rejected the API key.");
        assertThat(service.getModelSettings().modelName()).isEqualTo("vendor/model");
        assertThat(onFxThread(() -> resultLabel.getStyleClass().contains("unverified"))).isTrue();
    }

    @Test
    void settingsDialogTogglesRedactionAndShowsThePrivacyLog() throws Exception {
        List<CheckBox> boxes =
                onFxThread(
                        () ->
                                window
                                        .getSettingsDialog()
                                        .getDialogPane()
                                        .lookupAll(".redaction-toggle")
                                        .stream()
                                        .map(node -> (CheckBox) node)
                                        .toList());
        assertThat(boxes)
                .extracting(CheckBox::getText)
                .containsExactly("private-key", "card-number", "sin", "api-key");
        assertThat(boxes).allMatch(CheckBox::isSelected);

        CheckBox sin = boxes.get(2);
        onFxThread(sin::fire); // off
        assertThat(service.getRedactionCategories()).containsEntry("sin", false);
        assertThat(Files.readString(home.resolve("app/settings.json"))).contains("\"sin\"");
        CheckBox sinAgain =
                onFxThread(
                        () ->
                                window
                                        .getSettingsDialog()
                                        .getDialogPane()
                                        .lookupAll(".redaction-toggle")
                                        .stream()
                                        .map(node -> (CheckBox) node)
                                        .filter(box -> box.getText().equals("sin"))
                                        .findFirst()
                                        .orElseThrow());
        assertThat(onFxThread(sinAgain::isSelected)).isFalse();
        onFxThread(sinAgain::fire); // on again
        assertThat(service.getRedactionCategories()).containsEntry("sin", true);

        TextArea log = inDialog(".privacy-log");
        assertThat(onFxThread(() -> log.getText())).isEqualTo(SettingsDialog.NOTHING_SENT);
        onFxThread(() -> controller.onAskSubmitted("Is anything due this week?"));
        awaitDetail(".answer");
        onFxThread(
                () ->
                        ((Button) window.getSettingsDialog().getDialogPane().lookup(".refresh-log"))
                                .fire());
        assertThat(onFxThread(() -> log.getText()))
                .startsWith("1. ")
                .contains("vendor/model")
                .contains("Redacted: nothing")
                .contains("[user] Is anything due this week?");
    }
}
