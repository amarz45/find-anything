package io.github.amarz45.findanything.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.amarz45.findanything.agent.ConversationStore;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.ScriptedExtractor;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ScriptedLlmClient;
import io.github.amarz45.findanything.llm.ScriptedLlmClientFactory;
import io.github.amarz45.findanything.llm.ToolCall;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliAppTest {

    @TempDir Path home;
    Path docs;
    FindAnythingService service;
    CliApp app;

    @BeforeEach
    void setUp() throws Exception {
        docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("dentist.txt"), "Dentist appointment on March 4, 2026.");
        Files.writeString(
                docs.resolve("lecture.pdf"), "Slide one.\fMerge sort runs in O(n log n) time.");
        Files.writeString(docs.resolve("photo.bin"), "binary");
        Files.writeString(docs.resolve("corrupt.txt"), "bad");
        service =
                service(
                        home.resolve("app"),
                        new ScriptedLlmClientFactory(
                                new ScriptedLlmClient(new LlmResponse("OK", List.of()))));
        app = new CliApp(service);
    }

    /** The environment a user gets after copying .env.example and filling it in. */
    static final Map<String, String> ENV =
            Map.of(
                    "OPENROUTER_API_KEY", "sk-or-test-key",
                    "OPENROUTER_MODEL", "vendor/model",
                    "OLLAMA_MODEL", "llama");

    /** A service whose model is scripted; the facade shares the factory's privacy objects. */
    static FindAnythingService service(Path appData, ScriptedLlmClientFactory factory)
            throws Exception {
        SettingsStore store = new SettingsStore(appData.resolve("settings.json"), ENV);
        IndexConfig config = store.loadIndexConfig();
        // What the composition root does: the saved policy and toggles apply from the start.
        store.loadFolderPolicy()
                .getLocalOnlyFolders()
                .forEach(folder -> factory.folderPolicy.setLocalOnly(folder, true));
        store.loadDisabledRedactionCategories()
                .forEach(category -> factory.redactor.setCategoryEnabled(category, false));
        return new FindAnythingService(
                config,
                store,
                factory,
                factory.folderPolicy,
                factory.redactor,
                factory.privacyLog,
                new ConversationStore(appData.resolve("conversations"), store),
                new ScriptedExtractor(),
                new Chunker(300),
                new ChangeQueue(Duration.ZERO),
                appData.resolve("index"),
                Executors.newFixedThreadPool(2),
                Clock.systemDefaultZone());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    /** Replaces the service with one whose model is this scripted client. */
    void useModel(ScriptedLlmClient client) throws Exception {
        service.close();
        service = service(home.resolve("app"), new ScriptedLlmClientFactory(client));
        app = new CliApp(service);
    }

    static LlmResponse calls(String tool, Map<String, Object> arguments) {
        return new LlmResponse("", List.of(new ToolCall("call-1", tool, arguments)));
    }

    static LlmResponse says(String text) {
        return new LlmResponse(text, List.of());
    }

    @Test
    void askPrintsTheAnswerWithEachCitationMarked() throws Exception {
        Path lecture = docs.resolve("lecture.pdf");
        Path dentist = docs.resolve("dentist.txt");
        // A scripted client repeats its last reply, so each run gets a script of its own.
        Supplier<ScriptedLlmClient> script =
                () ->
                        new ScriptedLlmClient(
                                calls("search", Map.of("query", "merge sort")),
                                says(
                                        "Merge sort runs in O(n log n) time [1]. Your dentist"
                                                + " appointment is on March 11 [2].\n\n"
                                                + "CITATIONS:\n[1] file: "
                                                + lecture
                                                + "\npage: 2\n"
                                                + "quote: Merge sort runs in O(n log n) time.\n"
                                                + "[2] file: "
                                                + dentist
                                                + "\nquote: Dentist appointment on March 11,"
                                                + " 2026."));
        useModel(script.get());
        run("folders", "add", docs.toString());

        Run quiet = run("ask", "How", "fast", "is", "merge", "sort?");
        assertThat(quiet.exitCode()).isZero();
        assertThat(quiet.err()).isEmpty();
        assertThat(quiet.out())
                .startsWith("Merge sort runs in O(n log n) time [1].")
                .contains("Sources:")
                .contains("[1] [✓] " + lecture + ", page 2 (verified)")
                .contains("    \"Merge sort runs in O(n log n) time.\"")
                .contains("[2] [?] " + dentist + " (unverified: the quote was not found there)")
                .contains("1 of 2 citations could not be verified.")
                .doesNotContain("CITATIONS:")
                .doesNotContain("Files:");

        useModel(script.get());
        Run verbose = run("ask", "--verbose", "How fast is merge sort?");
        assertThat(verbose.exitCode()).isZero();
        assertThat(verbose.err())
                .contains("Step 1: asking the model")
                .contains("Step 1: search(query=\"merge sort\")")
                .contains("Step 1: search returned: ");
        assertThat(verbose.out()).doesNotContain("Step 1");

        Run noRequest = run("ask");
        assertThat(noRequest.exitCode()).isEqualTo(2);
        assertThat(noRequest.err()).contains("<request>");
    }

    @Test
    void askJsonGivesTheTraceAndContinueKeepsTheConversation() throws Exception {
        Path lecture = docs.resolve("lecture.pdf");
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        calls("search", Map.of("query", "merge sort", "limit", 3)),
                        says(
                                "It runs in O(n log n) time [1].\n\nCITATIONS:\n[1] file: "
                                        + lecture
                                        + "\npage: 2\nquote: Merge sort runs in O(n log n) time."),
                        says("Which file do you mean?"));
        useModel(client);
        run("folders", "add", docs.toString());

        Run none = run("ask", "--continue", "And the second one?");
        assertThat(none.exitCode()).isEqualTo(1);
        assertThat(none.err()).contains("There is no conversation to continue.");

        Run first = run("ask", "--json", "How fast is merge sort?");
        assertThat(first.exitCode()).isZero();
        JsonNode json = new ObjectMapper().readTree(first.out());
        assertThat(json.get("request").asText()).isEqualTo("How fast is merge sort?");
        assertThat(json.get("answer").asText()).isEqualTo("It runs in O(n log n) time [1].");
        assertThat(json.get("verified").asBoolean()).isTrue();
        assertThat(json.get("fallback").asBoolean()).isFalse();
        assertThat(json.get("model").get("backend").asText()).isEqualTo("cloud");
        assertThat(json.get("model").get("name").asText()).isEqualTo("vendor/model");
        assertThat(first.out()).doesNotContain("sk-or");
        JsonNode citation = json.get("citations").get(0);
        assertThat(citation.get("number").asInt()).isEqualTo(1);
        assertThat(citation.get("file").asText()).isEqualTo(lecture.toString());
        assertThat(citation.get("page").asInt()).isEqualTo(2);
        assertThat(citation.get("quote").asText()).isEqualTo("Merge sort runs in O(n log n) time.");
        assertThat(citation.get("status").asText()).isEqualTo("verified");
        assertThat(json.get("sourceFiles")).hasSize(1);
        assertThat(json.get("sourceFiles").get(0).asText()).isEqualTo(lecture.toString());
        assertThat(json.get("notices")).isEmpty();
        assertThat(json.get("toolCalls")).hasSize(1);
        assertThat(json.get("toolCalls").get(0).get("name").asText()).isEqualTo("search");
        assertThat(json.get("toolCalls").get(0).get("arguments").get("query").asText())
                .isEqualTo("merge sort");
        assertThat(json.get("toolCalls").get(0).get("arguments").get("limit").asInt()).isEqualTo(3);

        Run followUp = run("ask", "--continue", "--json", "And the second one?");
        assertThat(followUp.exitCode()).isZero();
        JsonNode second = new ObjectMapper().readTree(followUp.out());
        assertThat(second.get("conversation").asText())
                .isEqualTo(json.get("conversation").asText());
        assertThat(second.get("answer").asText()).isEqualTo("Which file do you mean?");
        assertThat(second.get("verified").asBoolean()).isFalse();
        assertThat(second.get("citations")).isEmpty();
        // Only this request's calls are listed, not the conversation's earlier ones.
        assertThat(second.get("toolCalls")).isEmpty();
        // The model was sent the first exchange again, then the follow-up.
        assertThat(client.requests.get(2).messages())
                .extracting(Message::content)
                .contains("How fast is merge sort?")
                .last()
                .isEqualTo("And the second one?");
        try (Stream<Path> saved = Files.list(home.resolve("app/conversations"))) {
            assertThat(saved).hasSize(1);
        }
    }

    @Test
    void askFallsBackToInstantSearchWhenTheModelIsUnavailable() throws Exception {
        useModel(new ScriptedLlmClient(new LlmException("Can't reach OpenRouter.")));
        run("folders", "add", docs.toString());

        Run plain = run("ask", "merge sort speed");
        assertThat(plain.exitCode()).isZero();
        assertThat(plain.out())
                .startsWith("The model is unavailable, so there is no answer.")
                .contains("Files:")
                .contains("1. " + docs.resolve("lecture.pdf"))
                .contains("page 2: Merge sort runs")
                .contains("Note: The model is unavailable: Can't reach OpenRouter.");

        JsonNode json = new ObjectMapper().readTree(run("ask", "--json", "merge sort").out());
        assertThat(json.get("fallback").asBoolean()).isTrue();
        assertThat(json.get("verified").asBoolean()).isFalse();
        assertThat(json.get("sourceFiles").get(0).asText())
                .isEqualTo(docs.resolve("lecture.pdf").toString());
        assertThat(json.get("notices").get(0).asText())
                .isEqualTo("The model is unavailable: Can't reach OpenRouter.");
    }

    record Run(int exitCode, String out, String err) {}

    Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = app.run(args, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void noArgumentsPrintsHelp() {
        Run run = run();
        assertThat(run.exitCode()).isZero();
        assertThat(run.out())
                .contains("Usage: findany")
                .contains("folders")
                .contains("search")
                .contains("show");
        assertThat(run("folders").out()).contains("Usage: findany folders");
        assertThat(run("bogus").exitCode()).isEqualTo(2);
    }

    @Test
    void addsAFolderThenSearchesAndShows() {
        assertThat(run("folders", "list").out()).contains("No search folders yet");

        Run add = run("folders", "add", docs.toString());
        assertThat(add.exitCode()).isZero();
        assertThat(add.out())
                .contains("Added " + docs)
                .contains("Index up to date: 4 files, 1 failed.");
        assertThat(run("folders", "list").out()).contains(docs.toString());

        Run search = run("search", "merge", "sort");
        assertThat(search.exitCode()).isZero();
        assertThat(search.out())
                .contains("1. " + docs.resolve("lecture.pdf"))
                .contains("page 2: Merge sort runs");

        Run list = run("search");
        assertThat(list.out())
                .contains("dentist.txt")
                .contains("photo.bin")
                .contains("(indexed by name only)")
                .contains("(extraction failed)");

        Run show = run("show", docs.resolve("lecture.pdf").toString(), "--highlight", "n log n");
        assertThat(show.exitCode()).isZero();
        assertThat(show.out())
                .contains("[page 1]")
                .contains("[page 2]")
                .contains("O([[n log n]]) time");
        assertThat(show.err()).contains("1 match for \"n log n\"");

        Run metadataOnly = run("show", docs.resolve("photo.bin").toString());
        assertThat(metadataOnly.out()).contains("No extracted text (indexed by name only)");

        Run status = run("index", "status", "--failed");
        assertThat(status.out())
                .contains("Mode: writer")
                .contains("Files: 4 indexed, 1 failed, 0 pending")
                .contains("corrupt.txt");

        assertThat(run("search", "nothing-matches-this").out()).contains("No results.");
    }

    @Test
    void exclusionsAndErrorsAreReported() {
        run("folders", "add", docs.toString());
        Run exclude = run("exclude", "add", "*.bin");
        assertThat(exclude.exitCode()).isZero();
        assertThat(run("exclude", "list", "--built-in").out())
                .contains("Global: *.bin")
                .contains("Built-in")
                .contains(".ssh");
        assertThat(run("search", "photo").out()).contains("No results.");

        Run bad = run("exclude", "add", "*");
        assertThat(bad.exitCode()).isEqualTo(1);
        assertThat(bad.err()).contains("findany: ").contains("exclude everything");

        Run missing = run("folders", "add", home.resolve("nope").toString());
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.err()).contains("No such file or folder");

        Run covered = run("folders", "add", docs.toString());
        assertThat(covered.exitCode()).isEqualTo(1);
        assertThat(covered.err()).contains("already covered");

        Run gone = run("show", docs.resolve("missing.txt").toString());
        assertThat(gone.exitCode()).isEqualTo(1);
        assertThat(gone.err()).contains("no longer exists");

        Run rebuild = run("index", "rebuild");
        assertThat(rebuild.exitCode()).isZero();
        assertThat(rebuild.out()).contains("Index up to date: 3 files");
        assertThat(run("folders", "remove", docs.toString()).out()).contains("Removed");
        assertThat(run("search").out()).contains("The index is empty");
    }

    @Test
    void resolvesTheDataFolderPerPlatform() {
        assertThat(CliApp.resolveHome(Map.of("FINDANY_HOME", "/tmp/x"), "Linux", "/home/u"))
                .isEqualTo(Path.of("/tmp/x").toAbsolutePath());
        assertThat(CliApp.resolveHome(Map.of(), "Linux", "/home/u"))
                .isEqualTo(Path.of("/home/u/.local/share/find-anything"));
        assertThat(CliApp.resolveHome(Map.of("XDG_DATA_HOME", "/data"), "Linux", "/home/u"))
                .isEqualTo(Path.of("/data/find-anything"));
        assertThat(
                        CliApp.resolveHome(
                                        Map.of("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local"),
                                        "Windows 11",
                                        "C:\\Users\\u")
                                .toString())
                .endsWith("FindAnything");
        assertThat(CliApp.resolveHome(Map.of(), "Mac OS X", "/Users/u"))
                .isEqualTo(Path.of("/Users/u/Library/Application Support/FindAnything"));
        assertThat(CliApp.formatSize(900)).isEqualTo("900 B");
        assertThat(CliApp.formatSize(90_000)).isEqualTo("88 KB");
        assertThat(CliApp.formatSize(3_000_000)).isEqualTo("2.9 MB");
    }

    @Test
    void modelSetTestsTheBackendThenSavesItWithoutTheKey() throws Exception {
        Run set = run("model", "set", "local", "--model", "tiny");
        assertThat(set.exitCode()).isZero();
        assertThat(set.out())
                .contains("Testing the local model tiny through Ollama at http://localhost:11434")
                .contains("Connection OK.")
                .contains("Saved. New conversations use the local model tiny");
        String saved = Files.readString(home.resolve("app/settings.json"));
        assertThat(saved).contains("\"LOCAL\"").contains("\"tiny\"").doesNotContain("sk-or");

        Run test = run("model", "test");
        assertThat(test.exitCode()).isZero();
        assertThat(test.out()).contains("local model tiny").contains("Connection OK.");

        Run cloud = run("model", "set", "CLOUD");
        assertThat(cloud.exitCode()).isZero();
        assertThat(cloud.out())
                .contains("cloud model vendor/model through OpenRouter")
                .contains("API key: set")
                .doesNotContain("sk-or");
        assertThat(Files.readString(home.resolve("app/settings.json")))
                .contains("\"CLOUD\"")
                .doesNotContain("sk-or");
        assertThat(run("model", "set", "cloud", "--model", "vendor/other").out())
                .contains("Saved. New conversations use the cloud model vendor/other");
        assertThat(run("model", "test").out()).contains("vendor/other");
    }

    @Test
    void modelSetReportsAFailedTestAndSavesNothing() throws Exception {
        Path appData = home.resolve("failing");
        try (FindAnythingService failing =
                service(
                        appData,
                        new ScriptedLlmClientFactory(
                                new ScriptedLlmClient(
                                        new LlmException(
                                                "Can't reach Ollama at http://localhost:11434."))))) {
            Run run = new Run(0, "", "");
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            int code =
                    new CliApp(failing)
                            .run(
                                    new String[] {"model", "set", "local"},
                                    new PrintWriter(out, true),
                                    new PrintWriter(err, true));
            run = new Run(code, out.toString(), err.toString());
            assertThat(run.exitCode()).isEqualTo(CliApp.FAILURE);
            assertThat(run.err())
                    .contains("findany: Can't reach Ollama at http://localhost:11434.");
            assertThat(run.out()).doesNotContain("Saved");
            assertThat(appData.resolve("settings.json")).doesNotExist();
        }
    }

    @Test
    void modelAloneAndABadBackendPrintUsage() {
        assertThat(run("model").out())
                .contains("Usage: findany model")
                .contains("set")
                .contains("test");
        Run bad = run("model", "set", "remote");
        assertThat(bad.exitCode()).isEqualTo(2);
        assertThat(bad.err()).contains("cloud").contains("local");
    }
}
