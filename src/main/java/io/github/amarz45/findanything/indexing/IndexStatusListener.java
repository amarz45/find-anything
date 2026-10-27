package io.github.amarz45.findanything.indexing;

/**
 * Observer of the index's status. {@link Indexer} calls it on the indexing worker thread after each
 * step, so a GUI listener must hand the update to its own thread.
 */
@FunctionalInterface
public interface IndexStatusListener {

    void onStatusChanged(IndexStatus status);
}
