package io.github.amarz45.findanything.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves conversations so they outlive the process (F12): one readable JSON file per conversation,
 * named by its id, in the conversations folder of the app's data folder. Writes replace the file
 * atomically, so a crash can't leave one half-written.
 *
 * <p>A file holds the requests and the answers, not the tool calls and their results: those are
 * copies of file text that would go stale, and the answer already says what was found.
 *
 * <p>A saved conversation records its model (backend, model name, base URL) but never the API key.
 * On loading, the key comes from the environment through {@link SettingsStore}, as it does for the
 * current settings.
 */
public final class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** An id names a file, so it may hold only letters, digits, hyphens and underscores. */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String EXTENSION = ".json";

    private final Path conversationsDirectory;
    private final SettingsStore settingsStore;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * @param conversationsDirectory the folder of the conversation files; created on the first save
     * @param settingsStore supplies the API key for a loaded conversation's backend
     */
    public ConversationStore(Path conversationsDirectory, SettingsStore settingsStore) {
        this.conversationsDirectory =
                Objects.requireNonNull(conversationsDirectory, "conversationsDirectory")
                        .toAbsolutePath()
                        .normalize();
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
    }

    /** Writes the conversation to its file, replacing what was saved before. */
    public synchronized void save(Conversation conversation) throws IOException {
        Path file = fileOf(conversation.getId());
        ObjectNode root = mapper.createObjectNode();
        root.put("id", conversation.getId());
        ModelSettings settings = conversation.getModelSettings();
        ObjectNode model = root.putObject("modelSettings");
        model.put("backendType", settings.backendType().name());
        model.put("modelName", settings.modelName());
        model.put("baseUrl", settings.baseUrl().toString());
        ArrayNode messages = root.putArray("messages");
        for (Message message : conversation.getMessages()) {
            if (!Conversation.isToolMessage(message)) {
                writeMessage(messages.addObject(), message);
            }
        }
        ArrayNode results = root.putArray("lastResults");
        for (SearchResult result : conversation.getLastResults()) {
            writeResult(results.addObject(), result);
        }
        Files.createDirectories(conversationsDirectory);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, mapper.writeValueAsString(root) + System.lineSeparator());
        try {
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Every saved conversation, the most recently saved first. A file that can't be read is logged
     * and left out, so one damaged conversation doesn't hide the others.
     */
    public synchronized List<Conversation> listAll() throws IOException {
        if (!Files.isDirectory(conversationsDirectory)) {
            return List.of();
        }
        record Saved(Path file, FileTime savedAt) {}
        List<Saved> saved = new ArrayList<>();
        try (Stream<Path> files = Files.list(conversationsDirectory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().endsWith(EXTENSION)
                        && Files.isRegularFile(file)) {
                    saved.add(new Saved(file, Files.getLastModifiedTime(file)));
                }
            }
        }
        saved.sort(
                Comparator.comparing(Saved::savedAt)
                        .reversed()
                        .thenComparing(entry -> entry.file().getFileName().toString()));
        List<Conversation> conversations = new ArrayList<>();
        for (Saved entry : saved) {
            try {
                conversations.add(read(entry.file()));
            } catch (IOException e) {
                log.warn("Skipping a saved conversation: {}", e.getMessage());
            }
        }
        return conversations;
    }

    /**
     * Loads one conversation by its id.
     *
     * @throws IOException when no conversation has that id, or its file isn't valid
     */
    public synchronized Conversation load(String id) throws IOException {
        Path file = fileOf(id);
        if (!Files.isRegularFile(file)) {
            throw new IOException("There is no saved conversation with the id " + id + ".");
        }
        return read(file);
    }

    public Path getConversationsDirectory() {
        return conversationsDirectory;
    }

    private Path fileOf(String id) {
        if (!VALID_ID.matcher(Objects.requireNonNull(id, "id")).matches()) {
            throw new IllegalArgumentException(
                    "A conversation id may hold only letters, digits, - and _: " + id);
        }
        return conversationsDirectory.resolve(id + EXTENSION);
    }

    private Conversation read(Path file) throws IOException {
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("it isn't a JSON object");
            }
            String id = text(root, "id");
            List<Message> messages = new ArrayList<>();
            for (JsonNode node : root.path("messages")) {
                messages.add(readMessage(node));
            }
            List<SearchResult> lastResults = new ArrayList<>();
            for (JsonNode node : root.path("lastResults")) {
                lastResults.add(readResult(node));
            }
            return new Conversation(
                    id, readModelSettings(root.path("modelSettings")), messages, lastResults);
        } catch (IOException | IllegalArgumentException | DateTimeException e) {
            throw new IOException(
                    "The conversation file " + file + " isn't valid: " + e.getMessage(), e);
        }
    }

    /** The saved backend, model and URL, with the key the environment has for that backend. */
    private ModelSettings readModelSettings(JsonNode node) {
        BackendType backendType = BackendType.valueOf(text(node, "backendType"));
        return new ModelSettings(
                backendType,
                text(node, "modelName"),
                URI.create(text(node, "baseUrl")),
                settingsStore.defaultModelSettings(backendType).apiKey());
    }

    /** Writes a request or a closing reply; tool calls and results are never saved. */
    private static void writeMessage(ObjectNode node, Message message) {
        node.put("role", message.role().name());
        node.put("content", message.content());
    }

    /** Reads a message, including the tool calls and results that files from before held. */
    private Message readMessage(JsonNode node) {
        List<Path> sourceFiles = new ArrayList<>();
        for (JsonNode path : node.path("sourceFiles")) {
            sourceFiles.add(Path.of(path.asText()));
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : node.path("toolCalls")) {
            Map<String, Object> arguments =
                    mapper.convertValue(
                            call.path("arguments"),
                            new TypeReference<LinkedHashMap<String, Object>>() {});
            toolCalls.add(
                    new ToolCall(
                            optionalText(call, "id"),
                            text(call, "name"),
                            arguments == null ? Map.of() : arguments));
        }
        return new Message(
                Message.Role.valueOf(text(node, "role")),
                text(node, "content"),
                sourceFiles,
                toolCalls,
                optionalText(node, "toolCallId"),
                optionalText(node, "toolName"));
    }

    private static void writeResult(ObjectNode node, SearchResult result) {
        IndexedFile file = result.file();
        node.put("path", file.path().toString());
        node.put("fileType", file.fileType());
        node.put("sizeBytes", file.sizeBytes());
        node.put("modifiedTime", file.modifiedTime().toString());
        node.put("contentHash", file.contentHash());
        node.put("extractionStatus", file.extractionStatus().name());
        node.put("score", result.score());
        Chunk chunk = result.bestChunk();
        if (chunk != null) {
            ObjectNode best = node.putObject("bestChunk");
            best.put("position", chunk.position());
            best.put("pageNumber", chunk.pageNumber());
            best.put("text", chunk.text());
        }
    }

    /** A saved result; its chunk comes back without the embedding, which only the index needs. */
    private static SearchResult readResult(JsonNode node) {
        IndexedFile file =
                new IndexedFile(
                        Path.of(text(node, "path")),
                        text(node, "fileType"),
                        node.path("sizeBytes").asLong(),
                        Instant.parse(text(node, "modifiedTime")),
                        text(node, "contentHash"),
                        ExtractionStatus.valueOf(text(node, "extractionStatus")));
        Chunk chunk = null;
        JsonNode best = node.get("bestChunk");
        if (best != null && best.isObject()) {
            chunk =
                    new Chunk(
                            best.path("position").asInt(),
                            best.path("pageNumber").asInt(),
                            text(best, "text"),
                            new float[0]);
        }
        return new SearchResult(file, chunk, node.path("score").asDouble());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("\"" + field + "\" is missing in " + node);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
