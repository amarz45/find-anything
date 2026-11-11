package io.github.amarz45.findanything.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The record of everything sent to cloud models (F13): a text file in the app's data folder with
 * one JSON object per line, one line per request, oldest first. Entries are only ever added.
 *
 * <p>Thread-safe: background request threads append while the UI thread reads.
 */
public final class PrivacyLog {

    private static final Logger log = LoggerFactory.getLogger(PrivacyLog.class);

    private final Path logFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param logFile the log's file; it and its folder are created on the first entry
     */
    public PrivacyLog(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
    }

    /**
     * Adds an entry at the end of the file.
     *
     * @throws IOException when the file can't be written; the caller must then not send the request
     */
    public synchronized void append(PrivacyLogEntry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        ObjectNode node = mapper.createObjectNode();
        node.put("timestamp", entry.timestamp().toString());
        node.put("modelName", entry.modelName());
        ArrayNode categories = node.putArray("redactedCategories");
        entry.redactedCategories().forEach(categories::add);
        ArrayNode excerpts = node.putArray("excerpts");
        for (PrivacyLogEntry.Excerpt excerpt : entry.excerpts()) {
            ObjectNode item = excerpts.addObject();
            item.put("role", excerpt.role());
            if (!excerpt.sourceFiles().isEmpty()) {
                ArrayNode sourceFiles = item.putArray("sourceFiles");
                excerpt.sourceFiles().forEach(path -> sourceFiles.add(path.toString()));
            }
            item.put("text", excerpt.text());
        }
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        // One line per entry: Jackson escapes line breaks inside the text.
        Files.writeString(
                logFile,
                mapper.writeValueAsString(node) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * The most recent entries, newest first. A line that can't be read is logged and left out, so
     * one damaged line doesn't hide the rest.
     *
     * @param limit the most entries to return, at least 1
     * @return an empty list when nothing has been sent to a cloud model
     * @throws IOException when the file can't be read
     */
    public synchronized List<PrivacyLogEntry> getEntries(int limit) throws IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        if (!Files.exists(logFile)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<PrivacyLogEntry> entries = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && entries.size() < limit; i--) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(read(mapper.readTree(line)));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn(
                        "Line {} of the privacy log {} can't be read: {}",
                        i + 1,
                        logFile,
                        e.getMessage());
            }
        }
        return entries;
    }

    public Path getLogFile() {
        return logFile;
    }

    private static PrivacyLogEntry read(JsonNode node) {
        List<PrivacyLogEntry.Excerpt> excerpts = new ArrayList<>();
        for (JsonNode item : node.path("excerpts")) {
            List<Path> sourceFiles = new ArrayList<>();
            for (JsonNode path : item.path("sourceFiles")) {
                sourceFiles.add(Path.of(path.asText()));
            }
            excerpts.add(
                    new PrivacyLogEntry.Excerpt(
                            text(item, "role"), sourceFiles, text(item, "text")));
        }
        List<String> categories = new ArrayList<>();
        for (JsonNode category : node.path("redactedCategories")) {
            categories.add(category.asText());
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(text(node, "timestamp"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("the timestamp isn't a date and time");
        }
        return new PrivacyLogEntry(timestamp, text(node, "modelName"), excerpts, categories);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("\"" + field + "\" is missing");
        }
        return value.asText();
    }
}
