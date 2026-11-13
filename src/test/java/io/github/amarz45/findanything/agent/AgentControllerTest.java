package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.config.SearchFolder;
import io.github.amarz45.findanything.llm.LlmClient;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmRequest;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.Message.Role;
import io.github.amarz45.findanything.llm.ScriptedLlmClient;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.search.SearchEngine;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.Chunk;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentControllerTest {

    static final ModelSettings LOCAL =
            new ModelSettings(
                    BackendType.LOCAL, "llama", ModelSettings.OLLAMA_DEFAULT_BASE_URL, "");
    static final ModelSettings CLOUD =
            new ModelSettings(
                    BackendType.CLOUD, "vendor/model", ModelSettings.OPENROUTER_BASE_URL, "");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"), ZoneOffset.UTC);
    static final String QUESTION = "When does my lease end?";
    static final ToolCall SEARCH = new ToolCall("call-1", "search", Map.of("query", "lease end"));
    static final ToolCall BAD_SEARCH = new ToolCall("call-0", "search", Map.of("query", "bad"));

    /** The lease's passage as the real search tool gives it, and the note that replaces it. */
    static final String LEASE_PASSAGE =
            "Best passage (page 2):\nThe lease ends on August 31, 2026.";

    static final String LEASE_PASSAGE_NOTE =
            "Best passage (page 2): already given above, in the results for \"lease ends\".";

    @TempDir Path indexFolder;
    @TempDir Path files;
    SearchIndex index;
    Path lease;
    Path notes;
    ScriptedTool searchTool;
    ToolRegistry toolRegistry;
    final FolderPolicy folderPolicy = new FolderPolicy();
    Conversation conversation;
    final List<String> progress = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        index = SearchIndex.open(indexFolder);
        lease =
                indexFile(
                        "lease.pdf", new Chunk(0, 2, "The lease ends on August 31, 2026.", none()));
        notes = indexFile("notes.md", new Chunk(0, 0, "Renew the lease in the spring.", none()));
        index.commit();
        searchTool =
                new ScriptedTool(
                        new ToolSpec(
                                "search",
                                "Searches the index.",
                                List.of(ToolSpec.Parameter.string("query", "The words.", true))),
                        ToolResult.success(
                                "2 files for \"lease end\", best match first:\n1. " + lease,
                                List.of(lease, notes)));
        toolRegistry = new ToolRegistry();
        toolRegistry.register(searchTool);
        conversation = new Conversation("c1", LOCAL);
    }

    @AfterEach
    void tearDown() throws Exception {
        index.close();
    }

    /** Writes a real file and indexes it with the size and time it has on disk. */
    Path indexFile(String name, Chunk chunk) throws IOException {
        Path path = files.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "the original bytes of " + name);
        index.put(
                new IndexedFile(
                        path,
                        IndexedFile.fileTypeOf(path),
                        Files.size(path),
                        Files.getLastModifiedTime(path).toInstant(),
                        "hash of " + name,
                        ExtractionStatus.OK),
                List.of(chunk));
        return path;
    }

    static float[] none() {
        return new float[0];
    }

    /** Puts the real search tool, over the indexed files, in place of the scripted one. */
    void useTheRealSearchTool() {
        SearchFolder searchFolder = new SearchFolder(files, false, true, List.of());
        IndexConfig indexConfig = new IndexConfig(indexFolder, List.of(searchFolder), List.of());
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new SearchTool(new SearchEngine(index, indexConfig)));
    }

    AgentController controller(int maxSteps, int maxConversationChars) {
        return new AgentController(
                toolRegistry,
                new PromptBuilder(),
                new ResponseParser(),
                new CitationVerifier(index),
                index,
                folderPolicy,
                maxSteps,
                maxConversationChars,
                CLOCK);
    }

    Answer run(LlmClient client) throws Exception {
        return controller(4, 100_000).run(QUESTION, conversation, client, progress::add);
    }

    static LlmResponse calls(ToolCall... toolCalls) {
        return new LlmResponse("", List.of(toolCalls));
    }

    static LlmResponse searches(String query) {
        return calls(new ToolCall("call-" + query, "search", Map.of("query", query)));
    }

    static LlmResponse says(String text) {
        return new LlmResponse(text, List.of());
    }

    /** A final reply in the answer format that cites the lease's page 2. */
    LlmResponse citing(String quote) {
        return says(
                "Your lease ends on August 31, 2026 [1].\n\nCITATIONS:\n[1] file: "
                        + lease
                        + "\npage: 2\nquote: "
                        + quote);
    }

    List<Role> roles() {
        return conversation.getMessages().stream().map(Message::role).toList();
    }

    static Message last(LlmRequest request) {
        return request.messages().get(request.messages().size() - 1);
    }

    static List<Path> paths(List<SearchResult> results) {
        return results.stream().map(result -> result.file().path()).toList();
    }

    @Test
    void aNewRequestDropsEarlierToolCallsAndResultsButKeepsItsOwn() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        calls(SEARCH),
                        citing("The lease ends on August 31, 2026."),
                        calls(SEARCH),
                        says("The rent isn't in your files."));
        run(client);
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);

        controller(4, 100_000).run("And the rent?", conversation, client, progress::add);
        // The first exchange is its request and answer; the second still has its own tool call.
        assertThat(roles())
                .containsExactly(
                        Role.USER,
                        Role.ASSISTANT,
                        Role.USER,
                        Role.ASSISTANT,
                        Role.TOOL,
                        Role.ASSISTANT);
        // The follow-up's first request to the model carried no old search result.
        LlmRequest followUp = client.requests.get(2);
        assertThat(followUp.messages()).extracting(Message::role).doesNotContain(Role.TOOL);
        assertThat(followUp.messages())
                .extracting(Message::content)
                .contains(QUESTION, "And the rent?");
    }

    @Test
    void aPassageIsGivenOncePerRequestAndAgainInTheNextRequest() throws Exception {
        useTheRealSearchTool();
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        searches("lease ends"),
                        searches("ends august"),
                        citing("The lease ends on August 31, 2026."),
                        searches("ends august"),
                        says("The rent isn't in your files."));
        run(client);

        // Each request to the model ends with the result of the search before it.
        assertThat(last(client.requests.get(1)).content()).contains(LEASE_PASSAGE);
        assertThat(last(client.requests.get(2)).content())
                .contains("1 file for \"ends august\"")
                .contains(LEASE_PASSAGE_NOTE)
                .doesNotContain("August 31, 2026");

        // The follow-up no longer has the first request's results, so the same search gives the
        // passage in full.
        controller(4, 100_000).run("And the rent?", conversation, client, progress::add);
        assertThat(last(client.requests.get(4)).content())
                .contains("1 file for \"ends august\"")
                .contains(LEASE_PASSAGE);
    }

    @Test
    void aPassageWithheldFromACloudModelIsGivenInFullByTheNextSearch() throws Exception {
        Path renewal =
                indexFile(
                        "private/renewal.md",
                        new Chunk(0, 0, "The lease renewal is due in May.", none()));
        index.commit();
        folderPolicy.setLocalOnly(files.resolve("private"), true);
        useTheRealSearchTool();

        // The first result holds the private file, so a cloud model is sent none of it, and the
        // second search has to give the lease's passage itself.
        Conversation cloudConversation = new Conversation("c2", CLOUD);
        ScriptedLlmClient cloudModel =
                new ScriptedLlmClient(
                        searches("lease ends"),
                        searches("ends august"),
                        citing("The lease ends on August 31, 2026."));
        controller(4, 100_000).run(QUESTION, cloudConversation, cloudModel, progress::add);
        assertThat(last(cloudModel.requests.get(1)).sourceFiles()).contains(lease, renewal);
        assertThat(last(cloudModel.requests.get(2)).sourceFiles()).containsExactly(lease);
        assertThat(last(cloudModel.requests.get(2)).content())
                .contains(LEASE_PASSAGE)
                .doesNotContain("already given above");

        // A local model is sent everything, so its second search names the first.
        ScriptedLlmClient localModel =
                new ScriptedLlmClient(
                        searches("lease ends"),
                        searches("ends august"),
                        citing("The lease ends on August 31, 2026."));
        run(localModel);
        assertThat(last(localModel.requests.get(1)).sourceFiles()).contains(lease, renewal);
        assertThat(last(localModel.requests.get(2)).content())
                .contains(LEASE_PASSAGE_NOTE)
                .doesNotContain("August 31, 2026");
    }

    @Test
    void runsTheModelsToolCallsThenReturnsTheVerifiedAnswer() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(calls(SEARCH), citing("The lease ends on August 31, 2026."));
        Answer answer = run(client);

        assertThat(answer.text()).isEqualTo("Your lease ends on August 31, 2026 [1].");
        assertThat(answer.citations())
                .containsExactly(
                        new Citation(
                                lease,
                                2,
                                "The lease ends on August 31, 2026.",
                                VerificationStatus.VERIFIED));
        assertThat(answer.isVerified()).isTrue();
        assertThat(answer.notices()).isEmpty();
        // Only the cited file is listed, not everything the search returned.
        assertThat(paths(answer.sourceFiles())).containsExactly(lease);
        assertThat(conversation.getLastResults()).isEqualTo(answer.sourceFiles());

        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
        assertThat(searchTool.executed).containsExactly(Map.of("query", "lease end"));
        assertThat(toolRegistry.getCallLog()).containsExactly(SEARCH);

        assertThat(client.requests).hasSize(2);
        LlmRequest first = client.requests.get(0);
        assertThat(first.messages().get(0).content()).contains("Today's date is 2026-11-04");
        assertThat(last(first).content()).isEqualTo(QUESTION);
        assertThat(first.toolSpecs()).isEqualTo(toolRegistry.getToolSpecs());
        assertThat(last(client.requests.get(1)).content())
                .startsWith(PromptBuilder.FILE_CONTENT_START + "\n2 files for");

        assertThat(progress)
                .containsExactly(
                        "Step 1: asking the model",
                        "Step 1: search(query=\"lease end\")",
                        "Step 1: search returned: 2 files for \"lease end\", best match first",
                        "Step 2: asking the model");
    }

    @Test
    void returnsAnInvalidCallsErrorToTheModelSoItCanCorrectTheCall() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        calls(BAD_SEARCH),
                        calls(SEARCH),
                        citing("The lease ends on August 31, 2026."));
        Answer answer = run(client);

        assertThat(answer.isVerified()).isTrue();
        assertThat(searchTool.executed).containsExactly(Map.of("query", "lease end"));
        Message error = last(client.requests.get(1));
        assertThat(error.role()).isEqualTo(Role.TOOL);
        assertThat(error.toolCallId()).isEqualTo("call-0");
        assertThat(error.content())
                .isEqualTo("Error: Invalid call to search: The query \"bad\" isn't allowed.");
        assertThat(progress)
                .contains(
                        "Step 1: search failed: Invalid call to search: The query \"bad\" isn't"
                                + " allowed.");
        // Both calls are in the trace, the rejected one too.
        assertThat(toolRegistry.getCallLog()).containsExactly(BAD_SEARCH, SEARCH);
    }

    @Test
    void stopsAtTheStepLimitWithTheFilesFoundSoFar() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient(calls(SEARCH));
        Answer answer = controller(2, 100_000).run(QUESTION, conversation, client, progress::add);

        assertThat(client.requests).hasSize(2);
        assertThat(answer.text()).isEqualTo(AgentController.STEP_LIMIT_TEXT);
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.isVerified()).isFalse();
        assertThat(answer.notices())
                .containsExactly(
                        "The step limit of 2 was reached before the model gave an answer. The"
                                + " files found so far are listed.");
        assertThat(paths(answer.sourceFiles())).containsExactly(lease, notes);
        assertThat(conversation.getLastResults()).isEqualTo(answer.sourceFiles());
        // The history ends with a reply, so the next request follows a complete exchange.
        assertThat(roles())
                .containsExactly(
                        Role.USER,
                        Role.ASSISTANT,
                        Role.TOOL,
                        Role.ASSISTANT,
                        Role.TOOL,
                        Role.ASSISTANT);
        assertThat(progress).endsWith("The step limit of 2 was reached");
    }

    @Test
    void asksOnceForTheAnswerFormatAndKeepsTheReplyThatFollowsIt() throws Exception {
        LlmResponse rejected = says("Your lease ends on August 31, 2026 [1].");
        LlmResponse corrected = citing("The lease ends on August 31, 2026.");
        ScriptedLlmClient client = new ScriptedLlmClient(rejected, corrected);
        Answer answer = run(client);

        assertThat(answer.isVerified()).isTrue();
        assertThat(answer.notices()).isEmpty();
        assertThat(client.requests).hasSize(2);
        List<Message> reformat = client.requests.get(1).messages();
        assertThat(reformat.get(reformat.size() - 2).content()).isEqualTo(rejected.text());
        assertThat(last(client.requests.get(1)).role()).isEqualTo(Role.USER);
        assertThat(last(client.requests.get(1)).content())
                .startsWith("Your last reply can't be used")
                .contains("The answer uses [1]");
        // The conversation keeps the request and the reply the answer came from, nothing else.
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(conversation.getMessages().get(1).content()).isEqualTo(corrected.text());
        assertThat(progress)
                .contains(
                        "The reply isn't in the answer format; asking the model to send it again");
    }

    @Test
    void aSecondFormatFailureGivesTheReplyUnverifiedWithANotice() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        calls(SEARCH), says("Your lease ends on August 31, 2026 [1]."));
        Answer answer = run(client);

        // One tool step, the reply, and one reformat request: the model isn't asked a third time.
        assertThat(client.requests).hasSize(3);
        assertThat(answer.text()).isEqualTo("Your lease ends on August 31, 2026 [1].");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.isVerified()).isFalse();
        assertThat(answer.notices()).containsExactly(AgentController.UNREADABLE_NOTICE);
        assertThat(paths(answer.sourceFiles())).containsExactly(lease, notes);
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
        assertThat(conversation.getMessages().get(3).content()).isEqualTo(answer.text());
    }

    @Test
    void aToolCallInPlaceOfTheReformattedReplyIsNotRun() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(says("It ends in August [1]."), calls(SEARCH));
        Answer answer = run(client);

        assertThat(client.requests).hasSize(2);
        assertThat(searchTool.executed).isEmpty();
        assertThat(toolRegistry.getCallLog()).isEmpty();
        assertThat(answer.text()).isEqualTo("It ends in August [1].");
        assertThat(answer.notices()).containsExactly(AgentController.UNREADABLE_NOTICE);
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT);
    }

    @Test
    void anEmptyReplyIsAskedForAgainAndThenReported() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient(says("  "));
        Answer answer = run(client);

        assertThat(client.requests).hasSize(2);
        // There is no reply to show the model, so the instruction follows the request directly.
        assertThat(client.requests.get(1).messages())
                .extracting(Message::role)
                .containsExactly(Role.SYSTEM, Role.USER, Role.USER);
        assertThat(answer.text()).isEqualTo(AgentController.EMPTY_REPLY_TEXT);
        assertThat(answer.isVerified()).isFalse();
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(conversation.getMessages().get(1).content())
                .isEqualTo(AgentController.EMPTY_REPLY_TEXT);
    }

    @Test
    void aCitationThatDoesNotMatchTheFileIsMarkedUnverified() throws Exception {
        Path madeUp = files.resolve("made-up.pdf");
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        says(
                                "It ends on August 31, 2027 [1], as the renewal says [2].\n\n"
                                        + "CITATIONS:\n[1] file: "
                                        + lease
                                        + "\npage: 2\nquote: The lease ends on August 31, 2027.\n"
                                        + "[2] file: "
                                        + madeUp
                                        + "\nquote: The lease is renewed."));
        Answer answer = run(client);

        assertThat(answer.citations())
                .extracting(Citation::status)
                .containsExactly(VerificationStatus.UNVERIFIED, VerificationStatus.UNVERIFIED);
        assertThat(answer.isVerified()).isFalse();
        // A file that isn't in the index can't be listed.
        assertThat(paths(answer.sourceFiles())).containsExactly(lease);
    }

    @Test
    void aModelFailureEndsTheRequestWithItsException() {
        LlmException unreachable =
                new LlmException("Can't reach Ollama at http://localhost:11434.");
        ScriptedLlmClient client = new ScriptedLlmClient(unreachable);

        assertThatThrownBy(() -> run(client)).isSameAs(unreachable);
        assertThat(roles()).containsExactly(Role.USER);
        assertThat(progress).containsExactly("Step 1: asking the model");
    }

    @Test
    void aModelFailureDuringTheReformatRequestStillGivesTheReply() throws Exception {
        LlmException unreachable = new LlmException("The model didn't answer within 60 seconds.");
        Answer answer = run(new FailingSecondCall(says("It ends in August [1]."), unreachable));

        assertThat(answer.text()).isEqualTo("It ends in August [1].");
        assertThat(answer.notices()).containsExactly(AgentController.UNREADABLE_NOTICE);
        assertThat(roles()).containsExactly(Role.USER, Role.ASSISTANT);
    }

    @Test
    void aModelFailureAfterAnEmptyReplyEndsTheRequestWithItsException() {
        LlmException unreachable = new LlmException("The model didn't answer within 60 seconds.");

        assertThatThrownBy(() -> run(new FailingSecondCall(says(""), unreachable)))
                .isSameAs(unreachable);
        assertThat(roles()).containsExactly(Role.USER);
    }

    @Test
    void trimsTheHistoryBeforeCallingTheModel() throws Exception {
        conversation.addUserMessage("An earlier question about something else entirely?");
        conversation.addAssistantMessage(says("An earlier answer that no longer fits."));
        ScriptedLlmClient client =
                new ScriptedLlmClient(citing("The lease ends on August 31, 2026."));
        controller(4, QUESTION.length()).run(QUESTION, conversation, client, progress::add);

        assertThat(client.requests.get(0).messages())
                .extracting(Message::role)
                .containsExactly(Role.SYSTEM, Role.USER);
        assertThat(conversation.getMessages().get(0).content()).isEqualTo(QUESTION);
    }

    @Test
    void refusesAnEmptyRequestAndLimitsBelowOne() {
        ScriptedLlmClient client = new ScriptedLlmClient(says("unused"));
        assertThatThrownBy(
                        () -> controller(4, 100_000).run(" ", conversation, client, progress::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(conversation.getMessages()).isEmpty();
        assertThatThrownBy(() -> controller(0, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller(4, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Answers the first request and fails every later one. */
    static final class FailingSecondCall implements LlmClient {

        private final LlmResponse first;
        private final LlmException failure;
        private boolean called;

        FailingSecondCall(LlmResponse first, LlmException failure) {
            this.first = first;
            this.failure = failure;
        }

        @Override
        public LlmResponse complete(LlmRequest request) throws LlmException {
            if (called) {
                throw failure;
            }
            called = true;
            return first;
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
