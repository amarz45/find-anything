package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.Citation;
import io.github.amarz45.findanything.agent.VerificationStatus;
import io.github.amarz45.findanything.core.Preview;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * The right-hand pane: a file's preview with its matches highlighted (F11), or the agent's
 * conversation (F07, F08, F09, F12) with the steps as they happen, then the answer with clickable,
 * marked citations. Clicking a citation switches to the preview; Back returns to the conversation.
 */
public final class DetailPane extends StackPane {

    static final String SELECT_A_RESULT = "Select a result to see its preview.";
    static final String NO_TEXT_MESSAGE =
            "No text was extracted from this file, so only its details are shown.";
    static final String ASK_A_QUESTION = "Ask a question about your files.";
    static final String THINKING = "Thinking…";
    static final String NOT_REVERIFIED = "Verification isn't kept for saved conversations.";
    static final String VERIFIED_MARK = "✓";
    static final String UNVERIFIED_MARK = "?";

    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");
    private static final Pattern PAGE_MARKER_LINE = Pattern.compile("(?m)^\\[page \\d+]$");
    private static final int QUOTE_CHARS = 300;

    // The preview
    private final VBox previewView = new VBox(6);
    private final Button back = new Button("◀ Back to conversation");
    private final Button openFile = new Button("Open file");
    private final Button showInFolder = new Button("Show in folder");
    private final Label fileName = new Label();
    private final Label fileDetails = new Label();
    private final Label previewMessage = new Label(SELECT_A_RESULT);
    private final TextFlow previewText = new TextFlow();
    private final ScrollPane previewScroll = new ScrollPane(previewText);
    private Path shownFile;
    private boolean scrollPending;

    // The conversation
    private final VBox exchanges = new VBox(10);
    private final ScrollPane conversationScroll = new ScrollPane(exchanges);
    private final Label emptyConversation = new Label(ASK_A_QUESTION);
    private Label progress;
    private boolean conversationShown;
    private Consumer<Citation> onCitationClicked = citation -> {};

    public DetailPane() {
        getStyleClass().add("detail-pane");
        getStylesheets().add(Styles.STYLESHEET);

        fileName.getStyleClass().add("detail-header");
        fileName.setWrapText(true);
        fileDetails.getStyleClass().add("detail-meta");
        fileDetails.setWrapText(true);
        previewMessage.getStyleClass().addAll("detail-message", "preview-message");
        previewMessage.setWrapText(true);
        back.getStyleClass().add("back-button");
        back.setOnAction(event -> showConversation());
        openFile.setOnAction(event -> openWithDesktop(Desktop.Action.OPEN));
        showInFolder.setOnAction(event -> openWithDesktop(Desktop.Action.BROWSE_FILE_DIR));
        setFileButtonsVisible(false);
        setVisible(back, false);
        HBox buttons = new HBox(6, back, openFile, showInFolder);
        previewText.setPadding(new Insets(8));
        previewText.heightProperty().addListener((observable, before, after) -> scrollIfPending());
        previewScroll.setFitToWidth(true);
        VBox.setVgrow(previewScroll, Priority.ALWAYS);
        previewView.setPadding(new Insets(8));
        previewView
                .getChildren()
                .addAll(buttons, fileName, fileDetails, previewMessage, previewScroll);

        emptyConversation.getStyleClass().addAll("detail-message", "conversation-empty");
        exchanges.setPadding(new Insets(8));
        exchanges.getChildren().add(emptyConversation);
        conversationScroll.setFitToWidth(true);

        getChildren().addAll(conversationScroll, previewView);
        show(previewView);
    }

    // ------------------------------------------------------------------ the preview (F11)

    /**
     * Shows a file's details and its text with the matches highlighted, scrolled to the first one.
     * A metadata-only preview shows the details and says why there is no text.
     */
    public void showPreview(Preview preview) {
        Objects.requireNonNull(preview, "preview");
        IndexedFile file = preview.file();
        shownFile = file.path();
        fileName.setText(String.valueOf(file.path().getFileName()));
        fileDetails.setText(describe(file));
        String problem = ResultsView.describeExtraction(file.extractionStatus());
        String message = preview.isMetadataOnly() ? NO_TEXT_MESSAGE : "";
        if (!problem.isEmpty()) {
            message = message.isEmpty() ? problem : message + " " + problem;
        }
        previewMessage.setText(message);
        setVisible(previewMessage, !message.isEmpty());
        previewText.getChildren().setAll(renderPreview(preview));
        setFileButtonsVisible(true);
        setVisible(back, conversationShown);
        show(previewView);
        scrollPending = !preview.highlights().isEmpty();
        if (scrollPending) {
            // The flow is laid out on a later pulse; the height listener usually gets there first.
            Platform.runLater(() -> Platform.runLater(this::scrollIfPending));
        } else {
            previewScroll.setVvalue(0);
        }
    }

    static String describe(IndexedFile file) {
        StringBuilder text = new StringBuilder();
        text.append(file.path());
        text.append("\n");
        text.append(file.fileType().isEmpty() ? "no extension" : file.fileType());
        text.append(", ").append(CliApp.formatSize(file.sizeBytes()));
        text.append(", modified ").append(CliApp.formatDate(file.modifiedTime()));
        return text.toString();
    }

    /**
     * The preview's text as {@code Text} nodes: highlighted ranges get the {@code highlight} style
     * and {@code [page N]} lines the {@code page-marker} style. Ranges outside the text are
     * clamped.
     */
    static List<Node> renderPreview(Preview preview) {
        List<Node> nodes = new ArrayList<>();
        String text = preview.text();
        int cursor = 0;
        for (Preview.Range range : preview.highlights()) {
            int start = Math.min(Math.max(range.start(), cursor), text.length());
            int end = Math.min(Math.max(range.end(), start), text.length());
            if (start > cursor) {
                addPlain(nodes, text.substring(cursor, start));
            }
            if (end > start) {
                nodes.add(styled(new Text(text.substring(start, end)), "highlight"));
            }
            cursor = Math.max(cursor, end);
        }
        if (cursor < text.length()) {
            addPlain(nodes, text.substring(cursor));
        }
        return nodes;
    }

    private static void addPlain(List<Node> nodes, String text) {
        Matcher marker = PAGE_MARKER_LINE.matcher(text);
        int cursor = 0;
        while (marker.find()) {
            if (marker.start() > cursor) {
                nodes.add(new Text(text.substring(cursor, marker.start())));
            }
            nodes.add(styled(new Text(marker.group()), "page-marker"));
            cursor = marker.end();
        }
        if (cursor < text.length()) {
            nodes.add(new Text(text.substring(cursor)));
        }
    }

    private void scrollIfPending() {
        if (!scrollPending) {
            return;
        }
        Node first = previewText.lookup(".highlight");
        double contentHeight = previewText.getLayoutBounds().getHeight();
        double viewportHeight = previewScroll.getViewportBounds().getHeight();
        if (first == null || contentHeight <= 0 || viewportHeight <= 0) {
            return;
        }
        scrollPending = false;
        double scrollable = contentHeight - viewportHeight;
        if (scrollable <= 0) {
            previewScroll.setVvalue(0);
            return;
        }
        // Put the first highlight a third of the way down the viewport.
        double target = first.getBoundsInParent().getMinY() - viewportHeight / 3;
        previewScroll.setVvalue(Math.max(0, Math.min(1, target / scrollable)));
    }

    /** Opens the shown file, or its folder, with the desktop's default application. */
    private void openWithDesktop(Desktop.Action action) {
        Path file = shownFile;
        if (file == null) {
            return;
        }
        // Desktop calls can block for a while on some desktops, so they never run on the UI thread.
        Thread thread = new Thread(() -> openWithDesktop(action, file), "findany-open-file");
        thread.setDaemon(true);
        thread.start();
    }

    private void openWithDesktop(Desktop.Action action, Path file) {
        String problem = null;
        try {
            if (!Desktop.isDesktopSupported()) {
                problem = "Opening files isn't supported on this desktop.";
            } else {
                Desktop desktop = Desktop.getDesktop();
                if (action == Desktop.Action.BROWSE_FILE_DIR
                        && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(file.toFile());
                } else if (desktop.isSupported(Desktop.Action.OPEN)) {
                    Path target = action == Desktop.Action.OPEN ? file : file.getParent();
                    desktop.open(target.toFile());
                } else {
                    problem = "Opening files isn't supported on this desktop.";
                }
            }
        } catch (IOException | RuntimeException e) {
            problem = "Could not open " + file + ": " + e.getMessage();
        }
        if (problem != null) {
            String message = problem;
            Platform.runLater(
                    () -> {
                        previewMessage.setText(message);
                        setVisible(previewMessage, true);
                    });
        }
    }

    private void setFileButtonsVisible(boolean visible) {
        setVisible(openFile, visible);
        setVisible(showInFolder, visible);
    }

    // ------------------------------------------------------------------ the conversation

    /** Empties the conversation view and shows it, for a new conversation. */
    public void clearConversation() {
        exchanges.getChildren().setAll(emptyConversation);
        progress = null;
        showConversation();
    }

    /** Brings the conversation back in front of a preview. */
    public void showConversation() {
        conversationShown = true;
        show(conversationScroll);
    }

    /** Appends the user's request and a progress line, and shows the conversation. */
    public void showRequest(String request) {
        Objects.requireNonNull(request, "request");
        exchanges.getChildren().remove(emptyConversation);
        exchanges.getChildren().add(label(request, "request"));
        progress = label(THINKING, "progress");
        exchanges.getChildren().add(progress);
        showConversation();
        scrollConversationToEnd();
    }

    /** Replaces the progress line with what the agent is doing now. */
    public void showAgentStep(String step) {
        Objects.requireNonNull(step, "step");
        if (progress == null) {
            progress = label(step, "progress");
            exchanges.getChildren().add(progress);
        } else {
            progress.setText(step);
        }
    }

    /**
     * Replaces the progress line with the answer: its text with each {@code [n]} marker as a link
     * marked verified or unverified, the sources, and any notices.
     */
    public void showAnswer(Answer answer) {
        Objects.requireNonNull(answer, "answer");
        if (progress != null) {
            exchanges.getChildren().remove(progress);
            progress = null;
        }
        exchanges.getChildren().remove(emptyConversation);
        exchanges.getChildren().add(answerNode(answer, true));
        scrollConversationToEnd();
    }

    /**
     * Appends an exchange from a saved conversation. Its citations open the preview like live ones,
     * but carry no verified or unverified mark: statuses aren't saved.
     */
    public void showPastExchange(String request, Answer answer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(answer, "answer");
        exchanges.getChildren().remove(emptyConversation);
        exchanges.getChildren().add(label(request, "request"));
        exchanges.getChildren().add(answerNode(answer, false));
        showConversation();
    }

    /** Called with the citation the user clicks, in the answer's text or in its sources. */
    public void setOnCitationClicked(Consumer<Citation> onCitationClicked) {
        this.onCitationClicked = Objects.requireNonNull(onCitationClicked, "onCitationClicked");
    }

    private VBox answerNode(Answer answer, boolean live) {
        VBox box = new VBox(6);
        box.getStyleClass().add("answer");
        TextFlow flow = new TextFlow();
        flow.getChildren().addAll(renderAnswerText(answer, live));
        box.getChildren().add(flow);
        List<Citation> citations = answer.citations();
        if (!citations.isEmpty()) {
            box.getChildren().add(label("Sources", "detail-meta"));
            for (int i = 0; i < citations.size(); i++) {
                box.getChildren().add(sourceRow(i + 1, citations.get(i), live));
            }
        }
        for (String notice : answer.notices()) {
            box.getChildren().add(label("Note: " + notice, "notice"));
        }
        return box;
    }

    /** The answer's text with each marker number turned into a citation link. */
    private List<Node> renderAnswerText(Answer answer, boolean live) {
        List<Node> nodes = new ArrayList<>();
        String text = answer.text();
        Matcher marker = MARKER.matcher(text);
        int cursor = 0;
        while (marker.find()) {
            if (marker.start() > cursor) {
                nodes.add(new Text(text.substring(cursor, marker.start())));
            }
            String[] numbers = marker.group(1).split(",");
            for (int i = 0; i < numbers.length; i++) {
                if (i > 0) {
                    nodes.add(new Text(" "));
                }
                nodes.add(markerNode(numbers[i].strip(), answer.citations(), live));
            }
            cursor = marker.end();
        }
        if (cursor < text.length()) {
            nodes.add(new Text(text.substring(cursor)));
        }
        return nodes;
    }

    private Node markerNode(String digits, List<Citation> citations, boolean live) {
        int number;
        try {
            number = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            number = 0;
        }
        if (number < 1 || number > citations.size()) {
            return new Text("[" + digits + "]");
        }
        Citation citation = citations.get(number - 1);
        Hyperlink link = new Hyperlink(markerText(number, citation.status(), live));
        link.getStyleClass().add(markerStyle(citation.status(), live));
        link.setPadding(new Insets(0, 2, 0, 2));
        link.setTooltip(new Tooltip(citationTitle(citation) + "\n" + statusText(citation, live)));
        link.setOnAction(event -> onCitationClicked.accept(citation));
        return link;
    }

    static String markerText(int number, VerificationStatus status, boolean live) {
        if (!live) {
            return "[" + number + "]";
        }
        String mark = status == VerificationStatus.VERIFIED ? VERIFIED_MARK : UNVERIFIED_MARK;
        return "[" + number + " " + mark + "]";
    }

    private static String markerStyle(VerificationStatus status, boolean live) {
        if (!live) {
            return "citation-neutral";
        }
        return status == VerificationStatus.VERIFIED ? "citation-verified" : "citation-unverified";
    }

    private static String citationTitle(Citation citation) {
        return citation.file().getFileName() + CliApp.Ask.place(citation);
    }

    private static String statusText(Citation citation, boolean live) {
        return live ? CliApp.Ask.describe(citation.status()) : NOT_REVERIFIED;
    }

    private VBox sourceRow(int number, Citation citation, boolean live) {
        Hyperlink link = new Hyperlink("[" + number + "] " + citationTitle(citation));
        link.setTooltip(new Tooltip(citation.file().toString()));
        link.setOnAction(event -> onCitationClicked.accept(citation));
        boolean unverified = live && citation.status() != VerificationStatus.VERIFIED;
        Label status = label(statusText(citation, live), unverified ? "unverified" : "detail-meta");
        VBox row = new VBox(2, new HBox(6, link, status));
        row.getStyleClass().add("source");
        if (unverified) {
            row.getStyleClass().add("unverified");
        }
        if (!citation.quote().isBlank()) {
            row.getChildren()
                    .add(label("“" + CliApp.snippet(citation.quote(), QUOTE_CHARS) + "”", "quote"));
        }
        return row;
    }

    private void scrollConversationToEnd() {
        Platform.runLater(() -> conversationScroll.setVvalue(1));
    }

    // ------------------------------------------------------------------ helpers

    private void show(Node view) {
        for (Node child : getChildren()) {
            setVisible(child, child == view);
        }
    }

    private static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Text styled(Text text, String styleClass) {
        text.getStyleClass().add(styleClass);
        return text;
    }
}
