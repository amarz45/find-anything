package io.github.amarz45.findanything.indexing;

/** The file's format isn't one text can be extracted from; it is indexed by name only (F02). */
public class UnsupportedFormatException extends Exception {

    private final String contentType;

    public UnsupportedFormatException(String contentType) {
        super("Unsupported format: " + contentType);
        this.contentType = contentType;
    }

    /** The detected MIME type. */
    public String getContentType() {
        return contentType;
    }
}
