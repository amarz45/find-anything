package io.github.amarz45.findanything.privacy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One request sent to a cloud model, as the privacy log records it (F13): when, to which model,
 * every message exactly as it went out (after local-only content was withheld and sensitive data
 * redacted), and which categories were redacted.
 *
 * @param timestamp when the request was sent
 * @param modelName the cloud model it went to
 * @param excerpts the request's messages, in order
 * @param redactedCategories the categories that had at least one match replaced in this request
 */
public record PrivacyLogEntry(
        Instant timestamp,
        String modelName,
        List<Excerpt> excerpts,
        List<String> redactedCategories) {

    /**
     * One message of the request.
     *
     * @param role who the message is from, in lowercase: system, user, assistant or tool
     * @param sourceFiles the files a tool result's text came from; empty for other messages. They
     *     are recorded so the user can see whose text was sent or withheld; the paths themselves
     *     aren't part of what is sent
     * @param text the message's text as sent
     */
    public record Excerpt(String role, List<Path> sourceFiles, String text) {
        public Excerpt {
            Objects.requireNonNull(role, "role");
            sourceFiles = List.copyOf(Objects.requireNonNull(sourceFiles, "sourceFiles"));
            Objects.requireNonNull(text, "text");
        }
    }

    public PrivacyLogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(modelName, "modelName");
        excerpts = List.copyOf(Objects.requireNonNull(excerpts, "excerpts"));
        redactedCategories =
                List.copyOf(Objects.requireNonNull(redactedCategories, "redactedCategories"));
    }
}
