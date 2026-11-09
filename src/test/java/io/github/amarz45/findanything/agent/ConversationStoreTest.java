package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

    static final String KEY = "sk-or-test-key";
    static final ModelSettings CLOUD =
            new ModelSettings(
                    BackendType.CLOUD, "vendor/model", ModelSettings.OPENROUTER_BASE_URL, KEY);
    static final ModelSettings LOCAL =
            new ModelSettings(
                    BackendType.LOCAL, "llama", ModelSettings.OLLAMA_DEFAULT_BASE_URL, "");
    static final Path LEASE = Path.of("data", "lease.pdf").toAbsolutePath();

    @TempDir Path home;
    Path directory;
    ConversationStore store;

    @BeforeEach
    void setUp() {
        directory = home.resolve("conversations");
        SettingsStore settingsStore =
                new SettingsStore(home.resolve("settings.json"), Map.of("OPENROUTER_API_KEY", KEY));
        store = new ConversationStore(directory, settingsStore);
    }

    /** A request, a search call with every kind of argument, its result, and the answer. */
    static Conversation conversation(String id, ModelSettings modelSettings) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", "lease \"end\"");
        arguments.put("limit", 5);
        arguments.put("file_type", null);
        ToolCall search = new ToolCall("call-1", "search", arguments);
        ToolCall noId = new ToolCall(null, "read_excerpt", Map.of("file", LEASE.toString()));
        Conversation conversation = new Conversation(id, modelSettings);
        conversation.addUserMessage("When does my lease end?\nThanks — é");
        conversation.addAssistantMessage(new LlmResponse("", List.of(search, noId)));
        conversation.addToolResult(search, ToolResult.success("ends August 31", List.of(LEASE)));
        conversation.addToolResult(noId, ToolResult.failure("No such file."));
        conversation.addAssistantMessage(new LlmResponse("On August 31, 2026.", List.of()));
        IndexedFile file =
                new IndexedFile(
                        LEASE,
                        "pdf",
                        2048,
                        Instant.parse("2025-08-14T16:00:00Z"),
                        "abc123",
                        ExtractionStatus.PARTIAL);
        conversation.setLastResults(
                List.of(
                        new SearchResult(
                                file, new Chunk(1, 2, "The lease ends.", new float[0]), 3.5),
                        new SearchResult(file, null, 0)));
        return conversation;
    }

    @Test
    void roundTripsRequestsAnswersResultsAndTheModel() throws Exception {
        Conversation saved = conversation("c1", CLOUD);
        store.save(saved);

        Conversation loaded = store.load("c1");
        assertThat(loaded.getId()).isEqualTo("c1");
        assertThat(loaded.getModelSettings()).isEqualTo(CLOUD);
        assertThat(loaded.getMessages())
                .containsExactly(
                        Message.user("When does my lease end?\nThanks — é"),
                        Message.assistant("On August 31, 2026.", List.of()));
        assertThat(loaded.getLastResults()).isEqualTo(saved.getLastResults());
        // Saving leaves the conversation in memory as it was, tool messages included.
        assertThat(saved.getMessages()).hasSize(5);
    }

    @Test
    void leavesToolCallsAndTheirResultsOutOfTheFile() throws Exception {
        store.save(conversation("c1", CLOUD));
        String json = Files.readString(directory.resolve("c1.json"));
        assertThat(json)
                .contains("On August 31, 2026.")
                .doesNotContain("ends August 31")
                .doesNotContain("No such file.")
                .doesNotContain("read_excerpt")
                .doesNotContain("toolCalls")
                .doesNotContain("TOOL");
    }

    @Test
    void stillLoadsAFileSavedWithToolCallsAndResults() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("old.json"),
                """
                {
                  "id" : "old",
                  "modelSettings" : {
                    "backendType" : "LOCAL",
                    "modelName" : "llama",
                    "baseUrl" : "http://localhost:11434"
                  },
                  "messages" : [
                    { "role" : "USER", "content" : "When does my lease end?" },
                    { "role" : "ASSISTANT", "content" : "", "toolCalls" : [
                      { "id" : "call-1", "name" : "search",
                        "arguments" : { "query" : "lease", "limit" : 5, "file_type" : null } },
                      { "id" : null, "name" : "read_excerpt", "arguments" : { "page" : 2 } }
                    ] },
                    { "role" : "TOOL", "content" : "ends August 31",
                      "toolCallId" : "call-1", "toolName" : "search" },
                    { "role" : "ASSISTANT", "content" : "On August 31, 2026." }
                  ],
                  "lastResults" : [ ]
                }
                """);

        List<Message> messages = store.load("old").getMessages();
        assertThat(messages)
                .extracting(Message::role)
                .containsExactly(
                        Message.Role.USER,
                        Message.Role.ASSISTANT,
                        Message.Role.TOOL,
                        Message.Role.ASSISTANT);
        assertThat(messages.get(1).toolCalls().get(0).arguments())
                .containsEntry("limit", 5)
                .containsEntry("file_type", null);
        assertThat(messages.get(1).toolCalls().get(1).id()).isNull();
        assertThat(messages.get(2).content()).isEqualTo("ends August 31");
        assertThat(messages.get(2).toolName()).isEqualTo("search");
    }

    @Test
    void neverWritesTheApiKeyAndRestoresItFromTheEnvironment() throws Exception {
        store.save(conversation("c1", CLOUD));
        String json = Files.readString(directory.resolve("c1.json"));
        assertThat(json).contains("\"backendType\" : \"CLOUD\"").contains("vendor/model");
        assertThat(json).doesNotContain(KEY).doesNotContain("apiKey");

        assertThat(store.load("c1").getModelSettings().apiKey()).isEqualTo(KEY);

        ConversationStore withoutKey =
                new ConversationStore(directory, new SettingsStore(home.resolve("settings.json")));
        ModelSettings settings = withoutKey.load("c1").getModelSettings();
        assertThat(settings.hasApiKey()).isFalse();
        assertThat(settings.modelName()).isEqualTo("vendor/model");
    }

    @Test
    void savingAgainReplacesTheFile() throws Exception {
        Conversation conversation = conversation("c1", LOCAL);
        store.save(conversation);
        conversation.addUserMessage("And the rent?");
        store.save(conversation);
        assertThat(store.load("c1").getMessages())
                .extracting(Message::content)
                .containsExactly(
                        "When does my lease end?\nThanks — é",
                        "On August 31, 2026.",
                        "And the rent?");
        try (var files = Files.list(directory)) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactly("c1.json");
        }
    }

    @Test
    void listsTheMostRecentlySavedFirstAndSkipsADamagedFile() throws Exception {
        assertThat(store.listAll()).isEmpty();
        store.save(conversation("older", LOCAL));
        store.save(conversation("newest", CLOUD));
        store.save(conversation("middle", LOCAL));
        setSavedAt("older", 1_000);
        setSavedAt("middle", 2_000);
        setSavedAt("newest", 3_000);
        Files.writeString(directory.resolve("damaged.json"), "{ not json");
        Files.writeString(directory.resolve("notes.txt"), "not a conversation");

        assertThat(store.listAll())
                .extracting(Conversation::getId)
                .containsExactly("newest", "middle", "older");
    }

    @Test
    void reportsAMissingOrInvalidConversation() throws Exception {
        assertThatThrownBy(() -> store.load("nothing"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no saved conversation with the id nothing");

        Files.createDirectories(directory);
        Files.writeString(directory.resolve("bad.json"), "{ \"id\": \"bad\" }");
        assertThatThrownBy(() -> store.load("bad"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad.json isn't valid")
                .hasMessageContaining("backendType");

        Files.writeString(
                directory.resolve("role.json"),
                """
                { "id": "role",
                  "modelSettings": { "backendType": "LOCAL", "modelName": "m",
                                     "baseUrl": "http://localhost:11434" },
                  "messages": [ { "role": "ROBOT", "content": "hi" } ] }
                """);
        assertThatThrownBy(() -> store.load("role"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("role.json isn't valid");
    }

    @Test
    void refusesAnIdThatIsNotASafeFileName() {
        assertThatThrownBy(() -> store.load("../settings"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(new Conversation("a/b", LOCAL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory).doesNotExist();
    }

    private void setSavedAt(String id, long epochSecond) throws IOException {
        Files.setLastModifiedTime(
                directory.resolve(id + ".json"), FileTime.from(Instant.ofEpochSecond(epochSecond)));
    }
}
