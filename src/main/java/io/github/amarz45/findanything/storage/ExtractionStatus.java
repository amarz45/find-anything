package io.github.amarz45.findanything.storage;

/** What text extraction produced for an indexed file (F02). */
public enum ExtractionStatus {
    /** Every page was extracted. */
    OK,
    /** Extraction stopped at the size limit; the first part of the file is indexed. */
    PARTIAL,
    /** Unsupported format: the file is indexed by name and metadata only. */
    METADATA_ONLY,
    /** The format is supported but the file has no text layer (for example a scanned PDF). */
    NO_TEXT,
    /** Corrupt, password-protected, or parsing hit a limit or crashed. */
    FAILED
}
