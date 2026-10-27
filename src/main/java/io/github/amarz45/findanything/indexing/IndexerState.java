package io.github.amarz45.findanything.indexing;

/** Where the index lifecycle is, as in state diagram SM01. */
public enum IndexerState {
    /** Trying to take the index's write lock. */
    OPENING,
    /** Another process owns the index; this one only searches and refreshes. */
    READ_ONLY,
    /** Nothing to do; waiting for a path to become ready. */
    IDLE,
    /** Rescanning a folder, comparing the disk with the index. */
    RECONCILING,
    /** Indexing a file (phase 1). */
    INDEXING,
    /** Embedding a batch of chunks (phase 2). */
    EMBEDDING,
    /** Finishing the current task, committing, and releasing the write lock. */
    STOPPING,
    /** The worker has stopped and the lock is released. */
    STOPPED
}
