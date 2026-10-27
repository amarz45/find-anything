package io.github.amarz45.findanything.storage;

/** How this process opened the index: the first process writes, every other one only reads. */
public enum IndexMode {
    /** This process holds the index's write lock and runs the indexing worker. */
    WRITER,
    /** Another process holds the write lock; this one searches and refreshes every few seconds. */
    READ_ONLY
}
