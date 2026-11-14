package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.PrivacyLogEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.StringConverter;

/**
 * The settings window (F13, F14): the model backend with "Test connection", a toggle per redaction
 * category, and the privacy log. It only displays what it is given and reports the user's actions
 * through callbacks; {@link GuiController} does the work. Non-modal, so the main window stays
 * usable while it is open.
 *
 * <p>The privacy log holds text from the user's files, so it stays hidden until the user asks for
 * it. The user can hide it again, and closing the window hides it too.
 *
 * <p>Left to the CLI: the global exclusion rules and rebuilding the index.
 */
public final class SettingsDialog extends Dialog<Void> {

    static final String TESTING = "Testing the connection…";
    static final String KEY_SET = "API key: set (from .env or the environment)";
    static final String KEY_MISSING = "API key: none. Put OPENROUTER_API_KEY in .env.";
    static final String LIMITATION = CliApp.Privacy.LIMITATION;
    static final String NOTHING_SENT = "Nothing has been sent to a cloud model.";
    static final String SHOW_LOG = "Show privacy log";
    static final String HIDE_LOG = "Hide privacy log";

    private final ChoiceBox<BackendType> backend = new ChoiceBox<>();
    private final TextField modelName = new TextField();
    private final Label keyLine = new Label();
    private final Button testConnection = new Button("Test connection");
    private final Label testResult = new Label();
    private final VBox redactionToggles = new VBox(4);
    private final Map<String, CheckBox> categoryBoxes = new LinkedHashMap<>();
    private final TextArea privacyLog = new TextArea();
    private final Button showLog = new Button(SHOW_LOG);
    private final Button refreshLog = new Button("Refresh");

    private boolean readOnly;
    private BiConsumer<BackendType, String> onTestBackend = (type, name) -> {};
    private BiConsumer<String, Boolean> onRedactionToggled = (category, enabled) -> {};
    private Runnable onShowPrivacyLog = () -> {};

    public SettingsDialog() {
        setTitle("Settings");
        initModality(Modality.NONE);
        setResizable(true);
        getDialogPane().getStylesheets().add(Styles.STYLESHEET);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        backend.getItems().addAll(BackendType.CLOUD, BackendType.LOCAL);
        backend.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(BackendType type) {
                        return type == null
                                ? ""
                                : type == BackendType.CLOUD
                                        ? "Cloud (OpenRouter)"
                                        : "Local (Ollama)";
                    }

                    @Override
                    public BackendType fromString(String text) {
                        throw new UnsupportedOperationException();
                    }
                });
        backend.getStyleClass().add("backend-choice");
        backend.valueProperty().addListener((observable, before, after) -> showKeyLine());
        modelName.getStyleClass().add("model-name");
        modelName.setPromptText("From .env when left empty");
        keyLine.getStyleClass().add("detail-meta");
        testConnection.getStyleClass().add("test-connection");
        testConnection.setOnAction(
                event -> {
                    testResult.getStyleClass().removeAll("citation-verified", "unverified");
                    testResult.setText(TESTING);
                    testConnection.setDisable(true);
                    onTestBackend.accept(backend.getValue(), modelName.getText());
                });
        testResult.getStyleClass().add("test-result");
        testResult.setWrapText(true);

        GridPane model = new GridPane();
        model.setHgap(8);
        model.setVgap(6);
        model.add(new Label("Backend"), 0, 0);
        model.add(backend, 1, 0);
        model.add(new Label("Model"), 0, 1);
        model.add(modelName, 1, 1);
        model.add(keyLine, 1, 2);
        model.add(new HBox(8, testConnection, testResult), 1, 3);
        GridPane.setHgrow(modelName, Priority.ALWAYS);

        Label limitation = new Label(LIMITATION);
        limitation.getStyleClass().add("detail-meta");
        limitation.setWrapText(true);
        limitation.setMaxWidth(520);

        privacyLog.getStyleClass().add("privacy-log");
        privacyLog.setEditable(false);
        privacyLog.setWrapText(true);
        privacyLog.setPrefRowCount(12);
        showLog.getStyleClass().add("show-privacy-log");
        showLog.setOnAction(
                event -> {
                    boolean show = !privacyLog.isVisible();
                    setPrivacyLogShown(show);
                    if (show) {
                        onShowPrivacyLog.run();
                    }
                });
        refreshLog.getStyleClass().add("refresh-log");
        refreshLog.setOnAction(event -> onShowPrivacyLog.run());
        setPrivacyLogShown(false);

        VBox content =
                new VBox(
                        8,
                        heading("Model"),
                        model,
                        heading("Privacy"),
                        new Label("Redacted before a request reaches a cloud model:"),
                        redactionToggles,
                        limitation,
                        new HBox(
                                8,
                                heading("Privacy log: what was sent to cloud models"),
                                showLog,
                                refreshLog),
                        privacyLog);
        content.setPadding(new Insets(8));
        VBox.setVgrow(privacyLog, Priority.ALWAYS);
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(620);
        setOnHidden(event -> setPrivacyLogShown(false));
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("detail-header");
        return label;
    }

    /**
     * Shows or hides the privacy log's text area together with its "Refresh" button. Hiding it also
     * drops its text. The window is then sized again to fit what it shows.
     */
    private void setPrivacyLogShown(boolean shown) {
        if (!shown) {
            privacyLog.setText(NOTHING_SENT);
        }
        privacyLog.setVisible(shown);
        privacyLog.setManaged(shown);
        refreshLog.setVisible(shown);
        refreshLog.setManaged(shown);
        showLog.setText(shown ? HIDE_LOG : SHOW_LOG);
        Scene scene = getDialogPane().getScene();
        if (scene != null && scene.getWindow() != null) {
            scene.getWindow().sizeToScene();
        }
    }

    // ------------------------------------------------------------------ what the controller shows

    /** Shows the current backend and model; the key line says whether a key is configured. */
    public void showModelSettings(ModelSettings settings) {
        Objects.requireNonNull(settings, "settings");
        backend.setValue(settings.backendType());
        modelName.setText(settings.modelName());
        showKeyLine(settings.hasApiKey());
    }

    private void showKeyLine() {
        showKeyLine(keyLine.getText().equals(KEY_SET));
    }

    private void showKeyLine(boolean keySet) {
        boolean cloud = backend.getValue() == BackendType.CLOUD;
        keyLine.setText(keySet ? KEY_SET : KEY_MISSING);
        keyLine.setVisible(cloud);
        keyLine.setManaged(cloud);
    }

    /** Shows the connection test's outcome (SD08). */
    public void showTestResult(boolean succeeded, String message) {
        testConnection.setDisable(false);
        testResult.getStyleClass().removeAll("citation-verified", "unverified");
        testResult.getStyleClass().add(succeeded ? "citation-verified" : "unverified");
        testResult.setText(Objects.requireNonNull(message, "message"));
    }

    /** Shows each redaction category with whether it is on, as a toggle. */
    public void showRedaction(Map<String, Boolean> categories) {
        Objects.requireNonNull(categories, "categories");
        redactionToggles.getChildren().clear();
        categoryBoxes.clear();
        categories.forEach(
                (category, enabled) -> {
                    CheckBox box = new CheckBox(category);
                    box.getStyleClass().add("redaction-toggle");
                    box.setSelected(enabled);
                    box.setDisable(readOnly);
                    box.setOnAction(event -> onRedactionToggled.accept(category, box.isSelected()));
                    categoryBoxes.put(category, box);
                    redactionToggles.getChildren().add(box);
                });
    }

    /**
     * Fills the privacy log with the entries, newest first (UC09); an empty log says so. The text
     * is visible only while the user has the log shown.
     */
    public void showPrivacyLog(List<PrivacyLogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            privacyLog.setText(NOTHING_SENT);
            return;
        }
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (PrivacyLogEntry entry : entries) {
            number++;
            text.append(CliApp.PrivacyLogCommand.describe(number, entry)).append('\n');
        }
        privacyLog.setText(text.toString());
    }

    /** In a read-only process the toggles are shown but can't be changed. */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        categoryBoxes.values().forEach(box -> box.setDisable(readOnly));
    }

    // ------------------------------------------------------------------ the user's actions

    /** Called with the chosen backend and model name when "Test connection" is clicked. */
    public void setOnTestBackend(BiConsumer<BackendType, String> onTestBackend) {
        this.onTestBackend = Objects.requireNonNull(onTestBackend, "onTestBackend");
    }

    /** Called with the category and its new state when a toggle is changed. */
    public void setOnRedactionToggled(BiConsumer<String, Boolean> onRedactionToggled) {
        this.onRedactionToggled = Objects.requireNonNull(onRedactionToggled, "onRedactionToggled");
    }

    /** Called when "Show privacy log" is clicked, and on each "Refresh". */
    public void setOnShowPrivacyLog(Runnable onShowPrivacyLog) {
        this.onShowPrivacyLog = Objects.requireNonNull(onShowPrivacyLog, "onShowPrivacyLog");
    }
}
