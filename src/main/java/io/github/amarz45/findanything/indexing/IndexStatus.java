package io.github.amarz45.findanything.indexing;

import java.util.Objects;

/**
 * A snapshot of the index's state for the status indicator (F03).
 *
 * @param state what the indexer is doing
 * @param pendingFiles paths waiting in the change queue
 * @param failedFiles indexed files whose extraction failed
 * @param chunksWithoutEmbeddings chunks still waiting for the embedding phase
 */
public record IndexStatus(
        IndexerState state, int pendingFiles, int failedFiles, int chunksWithoutEmbeddings) {

    public IndexStatus {
        Objects.requireNonNull(state, "state");
    }

    /** True when nothing is waiting and the worker is idle (or this process only reads). */
    public boolean isUpToDate() {
        return (state == IndexerState.IDLE || state == IndexerState.READ_ONLY) && pendingFiles == 0;
    }
}
