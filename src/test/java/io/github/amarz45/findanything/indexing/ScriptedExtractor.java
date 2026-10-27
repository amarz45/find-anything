package io.github.amarz45.findanything.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A test double for {@link TextExtractor}: reads text files directly, and the file name scripts the
 * failure. {@code *.bin} is an unsupported format, a name containing "corrupt" fails extraction,
 * one containing "locked" can't be read, and a {@code .pdf} is split into numbered pages at form
 * feeds.
 */
public final class ScriptedExtractor implements TextExtractor {

    @Override
    public ExtractedDocument extract(Path file)
            throws UnsupportedFormatException, IOException, ExtractionException {
        String name = file.getFileName().toString();
        if (name.endsWith(".bin")) {
            throw new UnsupportedFormatException("application/octet-stream");
        }
        if (name.contains("corrupt")) {
            throw new ExtractionException("the file is corrupt");
        }
        if (name.contains("locked")) {
            throw new IOException("locked by another program");
        }
        String text = Files.readString(file);
        if (name.endsWith(".pdf")) {
            return new ExtractedDocument("application/pdf", List.of(text.split("\f")), true, false);
        }
        return new ExtractedDocument("text/plain", List.of(text), false, false);
    }

    @Override
    public void close() {}
}
