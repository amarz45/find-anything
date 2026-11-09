package io.github.amarz45.findanything.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.agent.Answer;
import io.github.amarz45.findanything.agent.Conversation;
import io.github.amarz45.findanything.agent.ConversationStore;
import io.github.amarz45.findanything.config.AlreadyCoveredException;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.InvalidPatternException;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.IndexStatus;
import io.github.amarz45.findanything.indexing.IndexerState;
import io.github.amarz45.findanything.indexing.IsolatedTextExtractor;
import io.github.amarz45.findanything.indexing.ScriptedExtractor;
import io.github.amarz45.findanything.indexing.TextExtractor;
import io.github.amarz45.findanything.llm.LlmClientFactory;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ScriptedLlmClient;
import io.github.amarz45.findanything.llm.ScriptedLlmClientFactory;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.Redactor;
import io.github.amarz45.findanything.privacy.SensitivePattern;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexMode;
import io.github.amarz45.findanything.storage.InvalidFiltersException;
import io.github.amarz45.findanything.storage.SearchFilters;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindAnythingServiceTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    @TempDir Path home;

    FindAnythingService service(Path appData, TextExtractor extractor) throws Exception {
        return service(
                appData,
                extractor,
                new LlmClientFactory(
                        new Redactor(SensitivePattern.builtIn()),
                        new FolderPolicy(),
                        new PrivacyLog(appData.resolve("privacy-log.jsonl")),
                        CLOCK),
                Map.of());
    }

    FindAnythingService service(
            Path appData,
            TextExtractor extractor,
            LlmClientFactory llmClientFactory,
            Map<String, String> environment)
            throws Exception {
        SettingsStore store = new SettingsStore(appData.resolve("settings.json"), environment);
        IndexConfig config = store.loadIndexConfig();
        return new FindAnythingService(
                config,
                store,
                llmClientFactory,
                new ConversationStore(appData.resolve("conversations"), store),
                extractor,
                new Chunker(300),
                new ChangeQueue(Duration.ZERO),
                appData.resolve("index"),
                Executors.newFixedThreadPool(2),
                CLOCK);
    }

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T14:30:12Z"), ZoneOffset.UTC);
    static final String QUESTION = "When does my lease end?";

    /** Writes a two-page lease into a new folder; {@link ScriptedExtractor} splits it at \f. */
    Path leaseFolder() throws Exception {
        Path docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(
                docs.resolve("lease.pdf"),
                "This lease is between the landlord and the tenant.\f"
                        + "The lease ends on August 31, 2026.");
        Files.writeString(docs.resolve("groceries.txt"), "Milk, eggs and bread.");
        return docs;
    }

    static List<Message.Role> roles(Conversation conversation) {
        return conversation.getMessages().stream().map(Message::role).toList();
    }

    /** Generous because CI's Windows runner starts the Tika child JVM and parses far slower. */
    static final Duration WAIT = Duration.ofSeconds(180);

    static void await(BooleanSupplier condition, Supplier<String> state)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting; " + state.get());
            }
            Thread.sleep(20);
        }
    }

    static void awaitUpToDate(FindAnythingService service) throws InterruptedException {
        await(
                () -> service.getIndexStatus().isUpToDate(),
                () -> "index status " + service.getIndexStatus());
    }

    @Test
    void indexesTheCorpusAndAnswersSearchesAndPreviews() throws Exception {
        Path appData = home.resolve("app");
        TextExtractor extractor =
                new IsolatedTextExtractor(
                        appData.resolve("tika"), Duration.ofSeconds(120), 1024L * 1024 * 1024);
        try (FindAnythingService service = service(appData, extractor)) {
            assertThat(service.getIndexStatus().state()).isEqualTo(IndexerState.OPENING);
            assertThat(service.open(false)).isEqualTo(IndexMode.WRITER);
            service.addSearchFolder(CORPUS);
            awaitUpToDate(service);

            List<SearchResult> budget =
                    service.search("approved budget", SearchFilters.NONE).join();
            assertThat(budget.get(0).file().path())
                    .isEqualTo(CORPUS.resolve("work/project-budget-2026.txt"));
            assertThat(budget.get(0).bestChunk().text()).contains("48,500");

            List<SearchResult> resume = service.search("resume", SearchFilters.NONE, 5).join();
            assertThat(resume)
                    .extracting(r -> r.file().path())
                    .contains(CORPUS.resolve("work/resume-jordan-rivera.docx"));

            Preview lease =
                    service.preview(
                                    CORPUS.resolve("housing/lease-2025-2026.pdf"),
                                    "August 31, 2026")
                            .join();
            assertThat(lease.text()).contains("[page 1]").contains("[page 2]");
            assertThat(lease.highlights()).isNotEmpty();
            Preview.Range first = lease.highlights().get(0);
            assertThat(lease.text().substring(first.start(), first.end()))
                    .isEqualTo("August 31, 2026");
            assertThat(lease.text().lastIndexOf("[page 2]")).isLessThan(first.start());

            Preview photo = service.preview(CORPUS.resolve("edge/photo.png"), null).join();
            assertThat(photo.isMetadataOnly()).isTrue();
            assertThat(photo.file().extractionStatus()).isEqualTo(ExtractionStatus.METADATA_ONLY);

            assertThat(service.listFailedFiles())
                    .extracting(f -> f.path().getFileName().toString())
                    .containsExactlyInAnyOrder("empty.txt", "password-protected.pdf");
            assertThat(service.search("SECRET id_ed25519", SearchFilters.NONE).join()).isEmpty();
            assertThat(service.countIndexedFiles())
                    .isEqualTo(23); // 25 files minus the two built-in exclusions
        }
    }

    @Test
    void managesFoldersExclusionsAndPausing() throws Exception {
        Path docs = home.resolve("docs");
        Files.createDirectories(docs.resolve("drafts"));
        Files.writeString(docs.resolve("plan.txt"), "The launch is on April 20.");
        Files.writeString(docs.resolve("drafts/old.txt"), "An old draft of the plan.");
        List<IndexStatus> seen = new CopyOnWriteArrayList<>();
        try (FindAnythingService service = service(home.resolve("app"), new ScriptedExtractor())) {
            service.addStatusListener(seen::add);
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);
            assertThatThrownBy(() -> service.addSearchFolder(docs.resolve("drafts")))
                    .isInstanceOf(AlreadyCoveredException.class);

            service.addExclusion("drafts", docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(1);
            assertThatThrownBy(() -> service.addExclusion("*", null))
                    .isInstanceOf(InvalidPatternException.class);
            assertThatThrownBy(() -> service.removeExclusion("nothing", null))
                    .isInstanceOf(IllegalArgumentException.class);
            service.removeExclusion("drafts", docs);
            awaitUpToDate(service);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);

            service.setFolderPaused(docs, true);
            assertThat(service.search("plan", SearchFilters.NONE).join()).isEmpty();
            service.setFolderPaused(docs, false);
            assertThat(service.search("plan", SearchFilters.NONE).join()).hasSize(2);

            SearchFilters backwards =
                    new SearchFilters(
                            Set.of(),
                            LocalDate.of(2026, 2, 1),
                            LocalDate.of(2026, 1, 1),
                            -1,
                            -1,
                            null);
            assertThatThrownBy(() -> service.search("plan", backwards).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(InvalidFiltersException.class);

            Files.delete(docs.resolve("drafts/old.txt"));
            assertThatThrownBy(() -> service.preview(docs.resolve("drafts/old.txt"), null).join())
                    .hasCauseInstanceOf(FileNotFoundException.class);
            awaitUpToDate(service);
            assertThat(service.search("draft", SearchFilters.NONE).join()).isEmpty();

            service.removeSearchFolder(docs);
            awaitUpToDate(service);
            assertThat(service.countIndexedFiles()).isZero();
            assertThat(seen).isNotEmpty();
        }
        // the settings were saved: the folder was removed again
        assertThat(
                        new SettingsStore(home.resolve("app/settings.json"))
                                .loadIndexConfig()
                                .getSearchFolders())
                .isEmpty();
    }

    @Test
    void aSecondProcessOpensReadOnlyAndRefusesChanges() throws Exception {
        Path docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("a.txt"), "alpha");
        Path appData = home.resolve("app");
        try (FindAnythingService writer = service(appData, new ScriptedExtractor());
                FindAnythingService reader = service(appData, new ScriptedExtractor())) {
            writer.open(false);
            assertThat(reader.open(false)).isEqualTo(IndexMode.READ_ONLY);
            assertThat(reader.getIndexStatus().state()).isEqualTo(IndexerState.READ_ONLY);
            assertThat(reader.getIndexStatus().isUpToDate()).isTrue();
            assertThatThrownBy(() -> reader.addSearchFolder(docs))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("read-only");
            assertThatThrownBy(reader::rebuildIndex).isInstanceOf(IllegalStateException.class);

            writer.addSearchFolder(docs);
            awaitUpToDate(writer);
            // the reader's own config doesn't know the folder; search results are filtered by it
            assertThat(reader.search("alpha", SearchFilters.NONE).join()).isEmpty();
            reader.getIndexConfig().addFolder(docs);
            await(
                    () -> !reader.search("alpha", SearchFilters.NONE).join().isEmpty(),
                    () -> "the reader has not seen the writer's commit"); // after a refresh
        }
    }

    @Test
    void testsAndSetsTheModelBackendWithoutOpeningTheIndex() throws Exception {
        Map<String, String> env =
                Map.of("OPENROUTER_API_KEY", "sk-or-test", "OPENROUTER_MODEL", "vendor/model");
        ScriptedLlmClientFactory failing =
                new ScriptedLlmClientFactory(
                        new ScriptedLlmClient(new LlmException("rejected the API key")));
        try (FindAnythingService service =
                service(home.resolve("app"), new ScriptedExtractor(), failing, env)) {
            ModelSettings current = service.getModelSettings();
            assertThat(current.backendType()).isEqualTo(BackendType.CLOUD);
            assertThat(current.modelName()).isEqualTo("vendor/model");

            assertThatThrownBy(() -> service.testBackend(current).join())
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .isInstanceOf(LlmException.class)
                    .hasMessage("rejected the API key");
            assertThat(failing.requested).containsExactly(current);

            ModelSettings local = service.resolveModelSettings(BackendType.LOCAL, " mistral ");
            assertThat(local.backendType()).isEqualTo(BackendType.LOCAL);
            assertThat(local.modelName()).isEqualTo("mistral");
            assertThat(local.hasApiKey()).isFalse();
            assertThat(service.resolveModelSettings(BackendType.CLOUD, null)).isEqualTo(current);

            service.setBackend(local);
            assertThat(service.getModelSettings()).isEqualTo(local);
            assertThat(service.resolveModelSettings(BackendType.LOCAL, null)).isEqualTo(local);
            assertThat(Files.readString(home.resolve("app/settings.json")))
                    .contains("mistral")
                    .doesNotContain("sk-or-test");
        }
        try (FindAnythingService reopened =
                service(home.resolve("app"), new ScriptedExtractor(), failing, env)) {
            assertThat(reopened.getModelSettings().modelName()).isEqualTo("mistral");
        }
    }

    @Test
    void asksTheAgentWithTheConversationsOwnModelAndSavesTheConversation() throws Exception {
        Path docs = leaseFolder();
        Path lease = docs.resolve("lease.pdf");
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        new LlmResponse(
                                "",
                                List.of(
                                        new ToolCall(
                                                "call-1",
                                                "search",
                                                Map.of("query", "lease ends")))),
                        new LlmResponse(
                                "Your lease ends on August 31, 2026 [1].\n\nCITATIONS:\n[1] file: "
                                        + lease
                                        + "\npage: 2\nquote: The lease ends on August 31, 2026.",
                                List.of()),
                        new LlmResponse("Ask me about one of your files.", List.of()));
        ScriptedLlmClientFactory factory = new ScriptedLlmClientFactory(client);
        Path appData = home.resolve("app");
        Map<String, String> env = Map.of("OLLAMA_MODEL", "llama");
        List<String> progress = new CopyOnWriteArrayList<>();
        String id;
        try (FindAnythingService service =
                service(appData, new ScriptedExtractor(), factory, env)) {
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);

            Conversation conversation = service.newConversation();
            id = conversation.getId();
            assertThat(id).matches("20261105-143012-[0-9a-f]{4}");
            assertThat(conversation.getModelSettings().modelName()).isEqualTo("llama");
            assertThat(service.listConversations()).isEmpty(); // saved with its first answer

            Answer answer = service.ask(QUESTION, conversation, progress::add).join();
            assertThat(answer.text()).isEqualTo("Your lease ends on August 31, 2026 [1].");
            assertThat(answer.isVerified()).isTrue();
            assertThat(answer.citations().get(0).pageNumber()).isEqualTo(2);
            assertThat(answer.sourceFiles())
                    .extracting(r -> r.file().path())
                    .containsExactly(lease);
            assertThat(answer.notices()).isEmpty();
            // The real search tool ran on the index and its result went back to the model.
            assertThat(client.requests.get(1).messages())
                    .last()
                    .extracting(Message::content)
                    .asString()
                    .contains(lease.toString())
                    .contains("Best passage (page 2)");
            assertThat(progress).contains("Step 1: search(query=\"lease ends\")");

            // The current model changes; the conversation still talks to the one it started with.
            service.setBackend(service.resolveModelSettings(BackendType.LOCAL, "mistral"));
            service.ask("Thanks!", conversation, progress::add).join();
            assertThat(factory.requested).containsOnly(conversation.getModelSettings());
            // The follow-up carried the first request and its answer, not the old search result.
            assertThat(client.requests.get(2).messages())
                    .extracting(Message::role)
                    .doesNotContain(Message.Role.TOOL);
            assertThat(client.requests.get(2).messages())
                    .extracting(Message::content)
                    .contains(QUESTION, "Thanks!");
            assertThat(service.newConversation().getModelSettings().modelName())
                    .isEqualTo("mistral");

            assertThatThrownBy(() -> service.ask(" ", conversation, progress::add).join())
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        try (FindAnythingService reopened =
                service(appData, new ScriptedExtractor(), factory, env)) {
            assertThat(reopened.listConversations())
                    .extracting(Conversation::getId)
                    .containsExactly(id);
            Conversation saved = reopened.openConversation(id);
            assertThat(saved.getModelSettings().modelName()).isEqualTo("llama");
            // The file holds the requests and the answers, not the tool call and its result.
            assertThat(roles(saved))
                    .containsExactly(
                            Message.Role.USER,
                            Message.Role.ASSISTANT,
                            Message.Role.USER,
                            Message.Role.ASSISTANT);
            assertThatThrownBy(() -> reopened.openConversation("no-such-conversation"))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void answersWithInstantSearchWhenTheModelIsUnavailable() throws Exception {
        Path docs = leaseFolder();
        String reason =
                "Can't reach Ollama at http://localhost:11434. Start it with `ollama serve`.";
        ScriptedLlmClientFactory factory =
                new ScriptedLlmClientFactory(new ScriptedLlmClient(new LlmException(reason)));
        try (FindAnythingService service =
                service(home.resolve("app"), new ScriptedExtractor(), factory, Map.of())) {
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);
            Conversation conversation = service.newConversation();

            Answer answer = service.ask(QUESTION, conversation, step -> {}).join();
            assertThat(answer.text()).isEqualTo(FindAnythingService.FALLBACK_TEXT);
            assertThat(answer.notices())
                    .containsExactly(FindAnythingService.MODEL_UNAVAILABLE + reason);
            assertThat(answer.citations()).isEmpty();
            assertThat(answer.isVerified()).isFalse();
            assertThat(answer.sourceFiles())
                    .extracting(r -> r.file().path())
                    .containsExactly(docs.resolve("lease.pdf"));
            assertThat(conversation.getLastResults()).isEqualTo(answer.sourceFiles());
            assertThat(roles(conversation))
                    .containsExactly(Message.Role.USER, Message.Role.ASSISTANT);
            assertThat(service.openConversation(conversation.getId()).getMessages())
                    .isEqualTo(conversation.getMessages());

            // A paused folder is left out of the fallback, as it is from any search.
            service.setFolderPaused(docs, true);
            assertThat(service.ask(QUESTION, conversation, step -> {}).join().sourceFiles())
                    .isEmpty();
        }
    }

    @Test
    void theAgentsToolsSkipAPausedFolder() throws Exception {
        Path docs = leaseFolder();
        Path lease = docs.resolve("lease.pdf");
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        new LlmResponse(
                                "",
                                List.of(
                                        new ToolCall(
                                                "call-1", "search", Map.of("query", "lease ends")),
                                        new ToolCall(
                                                "call-2",
                                                "read_excerpt",
                                                Map.of("file", lease.toString())))),
                        new LlmResponse("None of your files says when the lease ends.", List.of()));
        try (FindAnythingService service =
                service(
                        home.resolve("app"),
                        new ScriptedExtractor(),
                        new ScriptedLlmClientFactory(client),
                        Map.of("OLLAMA_MODEL", "llama"))) {
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);
            service.setFolderPaused(docs, true);

            Conversation conversation = service.newConversation();
            Answer answer = service.ask(QUESTION, conversation, step -> {}).join();
            assertThat(answer.sourceFiles()).isEmpty();
            // What went back to the model holds nothing from the paused folder.
            List<Message> sent = client.requests.get(1).messages();
            assertThat(sent.get(sent.size() - 2).content())
                    .contains("No files match \"lease ends\".");
            assertThat(sent.get(sent.size() - 1).content())
                    .startsWith("Error: " + lease + " is in a search folder that is paused");
            assertThat(sent)
                    .extracting(Message::content)
                    .noneMatch(content -> content.contains("August 31, 2026"));
        }
    }

    @Test
    void answersWithInstantSearchWhenTheModelsClientCannotBeBuilt() throws Exception {
        Path docs = leaseFolder();
        ScriptedLlmClientFactory factory =
                new ScriptedLlmClientFactory(new LlmException("No OpenRouter API key."));
        try (FindAnythingService service =
                service(home.resolve("app"), new ScriptedExtractor(), factory, Map.of())) {
            service.open(false);
            service.addSearchFolder(docs);
            awaitUpToDate(service);
            Conversation conversation = service.newConversation();

            Answer answer = service.ask(QUESTION, conversation, step -> {}).join();
            assertThat(answer.notices())
                    .containsExactly(
                            FindAnythingService.MODEL_UNAVAILABLE + "No OpenRouter API key.");
            assertThat(answer.sourceFiles()).hasSize(1);
            // The request is recorded even though the agent never ran.
            assertThat(conversation.getMessages().get(0).content()).isEqualTo(QUESTION);
            assertThat(roles(conversation))
                    .containsExactly(Message.Role.USER, Message.Role.ASSISTANT);
        }
    }
}
