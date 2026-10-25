package io.github.amarz45.findanything.indexing;

/**
 * Text extraction failed for a supported format: the file is corrupt or password-protected, or
 * parsing hit the time or memory limit or crashed (F02). The file is marked as failed.
 */
public class ExtractionException extends Exception {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
