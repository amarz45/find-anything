package io.github.amarz45.findanything.ui;

import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * The results table (F06): name, folder, modification date, size and the best-matching passage of
 * each file. A file whose text couldn't be extracted (F02) is marked with a warning sign. Selecting
 * a row tells the controller, which shows the file's preview (F11). With no results, the table
 * suggests Ask mode.
 */
public final class ResultsView extends TableView<SearchResult> {

    private static final String WARNING_SIGN = "⚠ ";
    private static final int SNIPPET_CHARS = 160;

    private final Hyperlink tryAsk = new Hyperlink("Try Ask mode");
    private Consumer<SearchResult> onResultSelected = result -> {};
    private Runnable onTryAsk = () -> {};
    private boolean replacingResults;

    public ResultsView() {
        getStyleClass().add("results-view");
        getStylesheets().add(Styles.STYLESHEET);
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        getColumns().add(nameColumn());
        getColumns().add(column("Folder", 220, result -> folderOf(result.file())));
        getColumns()
                .add(
                        column(
                                "Modified",
                                120,
                                result -> CliApp.formatDate(result.file().modifiedTime())));
        getColumns()
                .add(column("Size", 70, result -> CliApp.formatSize(result.file().sizeBytes())));
        getColumns().add(column("Best passage", 300, ResultsView::snippetOf));

        getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (observable, previous, selected) -> {
                            if (selected != null && !replacingResults) {
                                onResultSelected.accept(selected);
                            }
                        });

        tryAsk.setOnAction(event -> onTryAsk.run());
        VBox placeholder = new VBox(4, new Label("No results."), tryAsk);
        placeholder.setAlignment(Pos.CENTER);
        setPlaceholder(placeholder);
    }

    /**
     * Replaces the rows. If the previously selected file is still among the results it stays
     * selected, without telling the controller again.
     */
    public void setResults(List<SearchResult> results) {
        Objects.requireNonNull(results, "results");
        SearchResult previous = getSelectionModel().getSelectedItem();
        replacingResults = true;
        try {
            getItems().setAll(results);
            if (previous != null) {
                Path path = previous.file().path();
                results.stream()
                        .filter(result -> result.file().path().equals(path))
                        .findFirst()
                        .ifPresent(getSelectionModel()::select);
            }
        } finally {
            replacingResults = false;
        }
    }

    /** The selected rows, in table order; one row at most for now (Compare is F10, Tier 2). */
    public List<SearchResult> getSelectedResults() {
        return List.copyOf(getSelectionModel().getSelectedItems());
    }

    /** Called with the result the user selects. */
    public void setOnResultSelected(Consumer<SearchResult> onResultSelected) {
        this.onResultSelected = Objects.requireNonNull(onResultSelected, "onResultSelected");
    }

    /** Called when the user clicks the "Try Ask mode" suggestion shown for no results. */
    public void setOnTryAsk(Runnable onTryAsk) {
        this.onTryAsk = Objects.requireNonNull(onTryAsk, "onTryAsk");
    }

    // ------------------------------------------------------------------ columns

    private TableColumn<SearchResult, IndexedFile> nameColumn() {
        TableColumn<SearchResult, IndexedFile> column = new TableColumn<>("Name");
        column.setPrefWidth(220);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().file()));
        column.setCellFactory(
                ignored ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(IndexedFile file, boolean empty) {
                                super.updateItem(file, empty);
                                getStyleClass().remove("warning");
                                if (empty || file == null) {
                                    setText(null);
                                    setTooltip(null);
                                    return;
                                }
                                setText(nameText(file));
                                String problem = describeExtraction(file.extractionStatus());
                                setTooltip(problem.isEmpty() ? null : new Tooltip(problem));
                                if (!problem.isEmpty()) {
                                    getStyleClass().add("warning");
                                }
                            }
                        });
        return column;
    }

    private static TableColumn<SearchResult, String> column(
            String title, double width, Function<SearchResult, String> text) {
        TableColumn<SearchResult, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(text.apply(data.getValue())));
        return column;
    }

    /** The file's name, with a warning sign in front when its text couldn't be fully extracted. */
    static String nameText(IndexedFile file) {
        String name = String.valueOf(file.path().getFileName());
        return describeExtraction(file.extractionStatus()).isEmpty() ? name : WARNING_SIGN + name;
    }

    /** Why a file's text is missing or incomplete, or "" when extraction worked. */
    static String describeExtraction(ExtractionStatus status) {
        return switch (status) {
            case OK -> "";
            case PARTIAL -> "Partially indexed: the file is larger than the extraction limit.";
            case METADATA_ONLY -> "Indexed by name only: the format isn't supported.";
            case NO_TEXT -> "No extractable text, for example a scanned PDF.";
            case FAILED -> "Extraction failed: the file may be corrupt or password-protected.";
        };
    }

    static String folderOf(IndexedFile file) {
        Path parent = file.path().getParent();
        return parent == null ? "" : parent.toString();
    }

    /** The best passage on one line, with its page or slide in front when the file has them. */
    static String snippetOf(SearchResult result) {
        if (result.bestChunk() == null) {
            return "";
        }
        String where = "";
        if (result.bestChunk().pageNumber() > 0) {
            String unit = result.file().fileType().equals("pptx") ? "slide " : "page ";
            where = unit + result.bestChunk().pageNumber() + ": ";
        }
        return where + CliApp.snippet(result.bestChunk().text(), SNIPPET_CHARS);
    }
}
