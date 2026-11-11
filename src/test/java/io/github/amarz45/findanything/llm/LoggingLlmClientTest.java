package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.PrivacyLogEntry;
import io.github.amarz45.findanything.privacy.PrivacyLogEntry.Excerpt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingLlmClientTest {

    static final ModelSettings CLOUD =
            new ModelSettings(
                    BackendType.CLOUD,
                    "vendor/model",
                    ModelSettings.OPENROUTER_BASE_URL,
                    "sk-test");
    static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Path LEASE = Path.of("docs", "lease.pdf").toAbsolutePath();

    @TempDir Path home;
    PrivacyLog privacyLog;

    @BeforeEach
    void setUp() {
        privacyLog = new PrivacyLog(home.resolve("privacy-log.jsonl"));
    }

    static LlmRequest request() {
        ToolCall search = new ToolCall("c1", "search", Map.of("query", "lease"));
        return new LlmRequest(
                List.of(
                        Message.system("Answer from the user's files."),
                        Message.user("When does my lease end? Card [redacted: card-number]"),
                        Message.assistant("Let me look.", List.of(search)),
                        Message.toolResult(search, "The lease ends on August 31.", List.of(LEASE))),
                List.of(new ToolSpec("search", "Searches.", List.of())),
                List.of("card-number"));
    }

    @Test
    void recordsEveryMessageAsSentBeforeTheRequestGoesOut() throws Exception {
        List<Integer> entriesWhenSent = new ArrayList<>();
        LlmClient inner =
                new LlmClient() {
                    @Override
                    public LlmResponse complete(LlmRequest request) {
                        try {
                            entriesWhenSent.add(privacyLog.getEntries(10).size());
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        return new LlmResponse("August 31.", List.of());
                    }

                    @Override
                    public boolean testConnection() {
                        return true;
                    }
                };
        LoggingLlmClient client = new LoggingLlmClient(inner, privacyLog, CLOUD, CLOCK);

        assertThat(client.complete(request()).text()).isEqualTo("August 31.");

        assertThat(entriesWhenSent).containsExactly(1);
        assertThat(privacyLog.getEntries(10))
                .containsExactly(
                        new PrivacyLogEntry(
                                NOW,
                                "vendor/model",
                                List.of(
                                        new Excerpt(
                                                "system",
                                                List.of(),
                                                "Answer from the user's files."),
                                        new Excerpt(
                                                "user",
                                                List.of(),
                                                "When does my lease end? Card"
                                                        + " [redacted: card-number]"),
                                        new Excerpt(
                                                "assistant",
                                                List.of(),
                                                "Let me look.\n[tool call: search {query=lease}]"),
                                        new Excerpt(
                                                "tool",
                                                List.of(LEASE),
                                                "The lease ends on August 31.")),
                                List.of("card-number")));
        // The key never reaches the log.
        assertThat(Files.readString(privacyLog.getLogFile())).doesNotContain("sk-test");
    }

    @Test
    void aRequestThatFailsIsStillRecorded() throws Exception {
        LoggingLlmClient client =
                new LoggingLlmClient(
                        new ScriptedLlmClient(new LlmException("Can't reach OpenRouter.")),
                        privacyLog,
                        CLOUD,
                        CLOCK);
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessage("Can't reach OpenRouter.");
        assertThat(privacyLog.getEntries(10)).hasSize(1);
    }

    @Test
    void aRequestThatCannotBeRecordedIsNotSent() throws Exception {
        Files.writeString(home.resolve("blocked"), "a file where the log's folder should be");
        PrivacyLog unwritable = new PrivacyLog(home.resolve("blocked/privacy-log.jsonl"));
        ScriptedLlmClient inner = new ScriptedLlmClient(new LlmResponse("OK", List.of()));
        LoggingLlmClient client = new LoggingLlmClient(inner, unwritable, CLOUD, CLOCK);

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageStartingWith(
                        "The request was not sent, because it could not be recorded in the"
                                + " privacy log "
                                + unwritable.getLogFile());
        assertThat(inner.requests).isEmpty();
    }

    @Test
    void theConnectionTestIsPassedOnAndNotRecorded() throws Exception {
        LoggingLlmClient client =
                new LoggingLlmClient(
                        new ScriptedLlmClient(new LlmResponse("OK", List.of())),
                        privacyLog,
                        CLOUD,
                        CLOCK);
        assertThat(client.testConnection()).isTrue();
        assertThat(privacyLog.getEntries(10)).isEmpty();
    }
}
