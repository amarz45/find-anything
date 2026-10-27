package io.github.amarz45.findanything.indexing;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Detects a file's type from its contents and returns its text page by page (F02). Implementations
 * must not be able to crash or hang the application: parsing runs with a time limit and a memory
 * limit, isolated from the app's process. The rest of the system never sees the parsing library's
 * types.
 */
public interface TextExtractor extends AutoCloseable {

    /**
     * Extracts the file's text.
     *
     * @throws UnsupportedFormatException when text can't be extracted from this kind of file
     * @throws IOException when the file can't be read, for example because another program has it
     *     locked; the caller retries later
     * @throws ExtractionException when the file is corrupt or password-protected, or parsing hit a
     *     limit or crashed
     */
    ExtractedDocument extract(Path file)
            throws UnsupportedFormatException, IOException, ExtractionException;

    /** Stops any helper process. */
    @Override
    void close() throws IOException;
}
