package io.github.amarz45.findanything.ui;

import static io.github.amarz45.findanything.ui.FxTestSupport.onFxThread;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.Citation;
import io.github.amarz45.findanything.agent.VerificationStatus;
import io.github.amarz45.findanything.core.Preview;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DetailPaneTest {

    static final Path LEASE = Path.of("/docs/lease.pdf").toAbsolutePath();
    static final Path NOTES = Path.of("/docs/notes.txt").toAbsolutePath();

    DetailPane pane;
    Stage stage;
    List<Citation> clicked = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        onFxThread(
                () -> {
                    pane = new DetailPane();
                    pane.setOnCitationClicked(clicked::add);
                    // Shown in a window so the scroll panes get skins and lookups reach their
                    // content.
                    stage = new Stage();
                    stage.setScene(new Scene(pane, 600, 400));
                    stage.show();
                });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFxThread(() -> stage.hide());
    }

    static IndexedFile file(Path path, ExtractionStatus status) {
        return new IndexedFile(
                path,
                IndexedFile.fileTypeOf(path),
                1_000,
                ResultsViewTest.MODIFIED,
                "hash",
                status);
    }

    static String textOf(Node node) {
        if (node instanceof Text text) {
            return text.getText();
        }
        if (node instanceof Label label) {
            return label.getText();
        }
        if (node instanceof Hyperlink link) {
            return link.getText();
        }
        return null;
    }

    /** The texts of the nodes matching the selector, in scene-graph order. */
    List<String> texts(String selector) throws Exception {
        return onFxThread(
                () -> {
                    pane.applyCss();
                    pane.layout();
                    return pane.lookupAll(selector).stream()
                            .map(DetailPaneTest::textOf)
                            .filter(text -> text != null)
                            .toList();
                });
    }

    /** The pieces of the latest answer's text: plain text and citation links, in order. */
    List<String> answerTexts() throws Exception {
        return onFxThread(
                () -> {
                    pane.applyCss();
                    pane.layout();
                    List<Node> answers = new ArrayList<>(pane.lookupAll(".answer"));
                    Parent answer = (Parent) answers.get(answers.size() - 1);
                    TextFlow flow = (TextFlow) answer.getChildrenUnmodifiable().get(0);
                    return flow.getChildren().stream().map(DetailPaneTest::textOf).toList();
                });
    }

    /** Whether the node and all of its ancestors are visible, so the user can see it. */
    boolean shown(String selector) throws Exception {
        return onFxThread(
                () -> {
                    Node node = pane.lookup(selector);
                    if (node == null) {
                        return false;
                    }
                    for (Node current = node; current != null; current = current.getParent()) {
                        if (!current.isVisible()) {
                            return false;
                        }
                    }
                    return true;
                });
    }

    @Test
    void previewHighlightsTheMatchesAndMarksPages() throws Exception {
        String text =
                "[page 1]\nThe tenant pays rent.\n[page 2]\nThe lease ends on August 31, 2026.";
        int start = text.indexOf("August 31, 2026");
        Preview preview =
                new Preview(
                        file(LEASE, ExtractionStatus.OK),
                        text,
                        List.of(new Preview.Range(start, start + "August 31, 2026".length())));
        onFxThread(() -> pane.showPreview(preview));

        assertThat(texts(".highlight")).containsExactly("August 31, 2026");
        assertThat(texts(".page-marker")).containsExactly("[page 1]", "[page 2]");
        assertThat(texts(".detail-header")).containsExactly("lease.pdf");
        assertThat(texts(".detail-meta").get(0)).contains(LEASE.toString()).contains("pdf");
        assertThat(shown(".preview-message")).isFalse();
        // Nothing to go back to: no conversation has been shown.
        assertThat(shown(".back-button")).isFalse();
        assertThat(shown(".highlight")).isTrue();
    }

    @Test
    void previewOfAFileWithoutTextSaysSo() throws Exception {
        Preview preview = new Preview(file(NOTES, ExtractionStatus.METADATA_ONLY), "", List.of());
        onFxThread(() -> pane.showPreview(preview));

        assertThat(texts(".highlight")).isEmpty();
        assertThat(shown(".preview-message")).isTrue();
        assertThat(texts(".preview-message").get(0))
                .startsWith(DetailPane.NO_TEXT_MESSAGE)
                .contains(ResultsView.describeExtraction(ExtractionStatus.METADATA_ONLY));
    }

    @Test
    void conversationShowsTheRequestTheStepsThenTheMarkedAnswer() throws Exception {
        onFxThread(() -> pane.showRequest("When does my lease end?"));
        assertThat(texts(".request")).containsExactly("When does my lease end?");
        assertThat(texts(".progress")).containsExactly(DetailPane.THINKING);
        assertThat(shown(".request")).isTrue();

        onFxThread(() -> pane.showAgentStep("Searching for lease end"));
        assertThat(texts(".progress")).containsExactly("Searching for lease end");

        Citation verified =
                new Citation(LEASE, 2, "ends on August 31, 2026", VerificationStatus.VERIFIED);
        Citation unverified =
                new Citation(NOTES, 0, "rent is $1,850", VerificationStatus.UNVERIFIED);
        Answer answer =
                new Answer(
                        "It ends on August 31, 2026 [1]. The rent is $1,850 [2]. See also [3].",
                        List.of(verified, unverified),
                        List.of(),
                        List.of("The step limit was reached."));
        onFxThread(() -> pane.showAnswer(answer));

        assertThat(texts(".progress")).isEmpty();
        assertThat(answerTexts())
                .containsExactly(
                        "It ends on August 31, 2026 ",
                        "[1 ✓]",
                        ". The rent is $1,850 ",
                        "[2 ?]",
                        ". See also ",
                        "[3]",
                        ".");
        assertThat(texts(".citation-verified")).containsExactly("[1 ✓]");
        assertThat(texts(".citation-unverified")).containsExactly("[2 ?]");
        assertThat(texts(".notice")).containsExactly("Note: The step limit was reached.");
        assertThat(texts(".source .hyperlink"))
                .containsExactly("[1] lease.pdf, page 2", "[2] notes.txt");
        assertThat(texts(".quote"))
                .containsExactly("“ends on August 31, 2026”", "“rent is $1,850”");
        assertThat(texts(".source.unverified .hyperlink")).containsExactly("[2] notes.txt");

        Hyperlink marker = onFxThread(() -> (Hyperlink) pane.lookup(".citation-unverified"));
        onFxThread(marker::fire);
        assertThat(clicked).containsExactly(unverified);
    }

    @Test
    void aPastExchangeHasNeutralMarkers() throws Exception {
        Citation citation = new Citation(LEASE, 2, "ends on August 31, 2026");
        Answer answer =
                new Answer(
                        "It ends on August 31, 2026 [1].", List.of(citation), List.of(), List.of());
        onFxThread(() -> pane.showPastExchange("When does my lease end?", answer));

        assertThat(texts(".request")).containsExactly("When does my lease end?");
        assertThat(texts(".citation-neutral")).containsExactly("[1]");
        assertThat(texts(".citation-verified")).isEmpty();
        assertThat(texts(".citation-unverified")).isEmpty();
        assertThat(texts(".source.unverified")).isEmpty();
        assertThat(texts(".source .detail-meta")).containsExactly(DetailPane.NOT_REVERIFIED);

        Hyperlink source = onFxThread(() -> (Hyperlink) pane.lookup(".source .hyperlink"));
        onFxThread(source::fire);
        assertThat(clicked).containsExactly(citation);
    }

    @Test
    void backReturnsFromAPreviewToTheConversation() throws Exception {
        onFxThread(() -> pane.showRequest("Where is the lease?"));
        Preview preview = new Preview(file(LEASE, ExtractionStatus.OK), "The lease.", List.of());
        onFxThread(() -> pane.showPreview(preview));

        assertThat(shown(".back-button")).isTrue();
        assertThat(shown(".detail-header")).isTrue();
        assertThat(shown(".request")).isFalse();

        onFxThread(() -> ((Button) pane.lookup(".back-button")).fire());
        assertThat(shown(".request")).isTrue();
        assertThat(shown(".detail-header")).isFalse();

        onFxThread(() -> pane.clearConversation());
        assertThat(texts(".request")).isEmpty();
        assertThat(shown(".conversation-empty")).isTrue();
        assertThat(texts(".conversation-empty")).containsExactly(DetailPane.ASK_A_QUESTION);
    }

    @Test
    void renderPreviewClampsRangesToTheText() {
        Preview preview =
                new Preview(
                        file(NOTES, ExtractionStatus.OK),
                        "short",
                        List.of(new Preview.Range(2, 50), new Preview.Range(1, 3)));
        List<Node> nodes = DetailPane.renderPreview(preview);
        assertThat(nodes).hasSize(2);
        assertThat(((Text) nodes.get(0)).getText()).isEqualTo("sh");
        assertThat(((Text) nodes.get(1)).getText()).isEqualTo("ort");
        assertThat(nodes.get(1).getStyleClass()).contains("highlight");
    }
}
