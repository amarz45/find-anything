package io.github.amarz45.findanything.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexerState;
import org.junit.jupiter.api.Test;

class MainWindowTest {

    static IndexStatus status(IndexerState state, int pending, int failed) {
        return new IndexStatus(state, pending, failed, 0);
    }

    @Test
    void statusTextSaysWhatTheIndexIsDoing() {
        assertThat(MainWindow.statusText(status(IndexerState.OPENING, 0, 0)))
                .isEqualTo("Opening the index…");
        assertThat(MainWindow.statusText(status(IndexerState.IDLE, 0, 0))).isEqualTo("Up to date");
        assertThat(MainWindow.statusText(status(IndexerState.INDEXING, 1, 0)))
                .isEqualTo("Indexing…");
        assertThat(MainWindow.statusText(status(IndexerState.RECONCILING, 12, 0)))
                .isEqualTo("Indexing… 12 pending");
        assertThat(MainWindow.statusText(status(IndexerState.IDLE, 0, 1)))
                .isEqualTo("Up to date · 1 file could not be read");
        assertThat(MainWindow.statusText(status(IndexerState.INDEXING, 3, 2)))
                .isEqualTo("Indexing… 3 pending · 2 files could not be read");
        assertThat(MainWindow.statusText(status(IndexerState.READ_ONLY, 0, 0)))
                .isEqualTo(MainWindow.READ_ONLY_STATUS);
        assertThat(MainWindow.statusText(status(IndexerState.STOPPING, 0, 0)))
                .isEqualTo("Stopping…");
    }

    @Test
    void statusTextShowsEmbeddingAndWhatWasLeftOut() {
        // While the indexer embeds (F05), the number of passages counts down.
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.EMBEDDING, 0, 0, 120)))
                .isEqualTo("Embedding… 120 passages left");
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.EMBEDDING, 0, 2, 1)))
                .isEqualTo("Embedding… 1 passage left · 2 files could not be read");
        // Only file names are left to embed.
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.EMBEDDING, 0, 0, 0)))
                .isEqualTo("Embedding…");
        // The model failed: the index is up to date, and says what search by meaning lacks.
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.IDLE, 0, 0, 8)))
                .isEqualTo("Up to date · 8 passages not embedded");
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.READ_ONLY, 0, 1, 3)))
                .isEqualTo(
                        MainWindow.READ_ONLY_STATUS
                                + " · 1 file could not be read · 3 passages not embedded");
        // Files come first: while they are being indexed, the status is about them.
        assertThat(MainWindow.statusText(new IndexStatus(IndexerState.INDEXING, 3, 0, 40)))
                .isEqualTo("Indexing… 3 pending");
    }
}
