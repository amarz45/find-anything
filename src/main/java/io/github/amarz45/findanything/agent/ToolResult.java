package io.github.amarz45.findanything.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a tool call produced: the content for the model, or the error that lets the model correct
 * its call (SD03).
 *
 * @param succeeded whether the tool ran and produced its content
 * @param content the text for the model, such as search results or an excerpt; empty on failure
 * @param errorMessage why the call failed, worded for the model; empty on success
 * @param sourceFiles the indexed files the content quotes from, which the redacting client uses to
 *     withhold local-only content from a cloud model (F14); empty on failure
 */
public record ToolResult(
        boolean succeeded, String content, String errorMessage, List<Path> sourceFiles) {

    public ToolResult {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(errorMessage, "errorMessage");
        sourceFiles = List.copyOf(Objects.requireNonNull(sourceFiles, "sourceFiles"));
        if (succeeded && !errorMessage.isEmpty()) {
            throw new IllegalArgumentException("A successful result has no error message.");
        }
        if (!succeeded && errorMessage.isBlank()) {
            throw new IllegalArgumentException("A failed result needs an error message.");
        }
    }

    /** The result of a tool that ran: its content and the files the content quotes from. */
    public static ToolResult success(String content, List<Path> sourceFiles) {
        return new ToolResult(true, content, "", sourceFiles);
    }

    /** The result of a call that was rejected or couldn't be carried out. */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, "", errorMessage, List.of());
    }
}
