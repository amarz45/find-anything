package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.Message.Role;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.ExtractionStatus;
import io.github.amarz45.findanything.storage.IndexedFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTest {

    static final ModelSettings LOCAL =
            new ModelSettings(
                    BackendType.LOCAL, "llama", ModelSettings.OLLAMA_DEFAULT_BASE_URL, "");
    static final Path LEASE = Path.of("data", "lease.pdf").toAbsolutePath();
    static final ToolCall SEARCH = new ToolCall("call-1", "search", Map.of("query", "lease"));

    @Test
    void recordsARequestTheModelsToolCallItsResultAndTheAnswerInOrder() {
        Conversation conversation = new Conversation("c1", LOCAL);
        conversation.addUserMessage("When does my lease end?");
        conversation.addAssistantMessage(new LlmResponse("", List.of(SEARCH)));
        conversation.addToolResult(SEARCH, ToolResult.success("ends August 31", List.of(LEASE)));
        conversation.addAssistantMessage(new LlmResponse("On August 31, 2026.", List.of()));

        List<Message> messages = conversation.getMessages();
        assertThat(messages)
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
        assertThat(messages.get(0).content()).isEqualTo("When does my lease end?");
        assertThat(messages.get(1).toolCalls()).containsExactly(SEARCH);
        assertThat(messages.get(2).content()).isEqualTo("ends August 31");
        assertThat(messages.get(2).sourceFiles()).containsExactly(LEASE);
        assertThat(messages.get(2).toolCallId()).isEqualTo("call-1");
        assertThat(messages.get(2).toolName()).isEqualTo("search");
        assertThat(messages.get(3).content()).isEqualTo("On August 31, 2026.");
        assertThat(messages.get(3).toolCalls()).isEmpty();
    }

    @Test
    void addsAFailedToolCallAsAnErrorTheModelCanCorrect() {
        Conversation conversation = new Conversation("c1", LOCAL);
        conversation.addToolResult(SEARCH, ToolResult.failure("Invalid call to search: no date."));
        Message message = conversation.getMessages().get(0);
        assertThat(message.content()).isEqualTo("Error: Invalid call to search: no date.");
        assertThat(message.sourceFiles()).isEmpty();
    }

    @Test
    void keepsItsModelAndIdAndHandsOutCopies() {
        List<Message> saved = new ArrayList<>(List.of(Message.user("hello")));
        Conversation conversation = new Conversation("c1", LOCAL, saved, List.of());
        saved.clear();
        assertThat(conversation.getId()).isEqualTo("c1");
        assertThat(conversation.getModelSettings()).isEqualTo(LOCAL);
        assertThat(conversation.getMessages()).hasSize(1);
        assertThatThrownBy(() -> conversation.getMessages().add(Message.user("x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new Conversation(" ", LOCAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remembersTheLatestResults() {
        Conversation conversation = new Conversation("c1", LOCAL);
        assertThat(conversation.getLastResults()).isEmpty();
        SearchResult result =
                new SearchResult(
                        new IndexedFile(LEASE, "pdf", 10, Instant.EPOCH, "h", ExtractionStatus.OK),
                        null,
                        1.0);
        conversation.setLastResults(List.of(result));
        assertThat(conversation.getLastResults()).containsExactly(result);
    }

    @Test
    void trimsByDroppingTheOldestWholeExchanges() {
        Conversation conversation = new Conversation("c1", LOCAL);
        exchange(conversation, "first question", "first answer");
        exchange(conversation, "second question", "second answer");
        conversation.addUserMessage("third question");
        int thirdLength = "third question".length();
        int total =
                lengthOfExchange("first question", "first answer")
                        + lengthOfExchange("second question", "second answer")
                        + thirdLength;

        conversation.trimToFit(total);
        assertThat(conversation.getMessages()).hasSize(9);

        // One character short of everything: only the oldest exchange goes.
        conversation.trimToFit(total - 1);
        List<Message> messages = conversation.getMessages();
        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).content()).isEqualTo("second question");
        assertThat(messages)
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.USER);

        conversation.trimToFit(thirdLength);
        assertThat(conversation.getMessages())
                .extracting(Message::content)
                .containsExactly("third question");
    }

    @Test
    void neverDropsTheLatestExchangeEvenWhenItIsTooLong() {
        Conversation conversation = new Conversation("c1", LOCAL);
        exchange(conversation, "first question", "first answer");
        exchange(conversation, "second question", "second answer");
        conversation.trimToFit(1);
        assertThat(conversation.getMessages()).hasSize(4);
        assertThat(conversation.getMessages().get(0).content()).isEqualTo("second question");
        assertThatThrownBy(() -> conversation.trimToFit(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dropsToolCallsAndResultsButKeepsEachRequestAndAnswer() {
        Conversation conversation = new Conversation("c1", LOCAL);
        exchange(conversation, "first question", "first answer");
        conversation.addUserMessage("second question");
        conversation.addAssistantMessage(new LlmResponse("Let me look.", List.of(SEARCH)));
        conversation.addToolResult(SEARCH, ToolResult.failure("No such file."));
        conversation.addAssistantMessage(new LlmResponse("second answer", List.of()));

        conversation.dropToolMessages();
        assertThat(conversation.getMessages())
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT);
        assertThat(conversation.getMessages())
                .extracting(Message::content)
                .containsExactly(
                        "first question", "first answer", "second question", "second answer");

        conversation.dropToolMessages(); // nothing left to drop
        assertThat(conversation.getMessages()).hasSize(4);
    }

    /** A request, a search call, its result, and the answer. */
    private static void exchange(Conversation conversation, String question, String answer) {
        conversation.addUserMessage(question);
        conversation.addAssistantMessage(new LlmResponse("", List.of(SEARCH)));
        conversation.addToolResult(SEARCH, ToolResult.success("found", List.of(LEASE)));
        conversation.addAssistantMessage(new LlmResponse(answer, List.of()));
    }

    private static int lengthOfExchange(String question, String answer) {
        return question.length()
                + SEARCH.name().length()
                + SEARCH.arguments().toString().length()
                + "found".length()
                + answer.length();
    }
}
