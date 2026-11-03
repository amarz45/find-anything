package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

    static final ToolCall SEARCH = new ToolCall("call-1", "search", Map.of("query", "lease"));

    @Test
    void factoriesSetTheRoleAndLeaveTheRestEmpty() {
        assertThat(Message.system("Today is Monday."))
                .isEqualTo(
                        new Message(
                                Message.Role.SYSTEM,
                                "Today is Monday.",
                                List.of(),
                                List.of(),
                                null,
                                null));
        assertThat(Message.user("When does my lease end?").role()).isEqualTo(Message.Role.USER);

        Message assistant = Message.assistant("", List.of(SEARCH));
        assertThat(assistant.role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(assistant.toolCalls()).containsExactly(SEARCH);
        assertThat(assistant.toolCallId()).isNull();

        Path lease = Path.of("housing", "lease-2025-2026.pdf");
        Message result =
                Message.toolResult(SEARCH, "1 result: lease-2025-2026.pdf", List.of(lease));
        assertThat(result.role()).isEqualTo(Message.Role.TOOL);
        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("search");
        assertThat(result.sourceFiles()).containsExactly(lease);
    }

    @Test
    void aToolResultWithoutAnIdIsAllowedForProvidersThatGiveNone() {
        ToolCall call = new ToolCall(null, "search", Map.of());
        Message result = Message.toolResult(call, "nothing found", List.of());
        assertThat(result.toolCallId()).isNull();
        assertThat(result.toolName()).isEqualTo("search");
    }

    @Test
    void onlyAssistantMessagesMakeToolCallsAndOnlyToolMessagesAnswerThem() {
        assertThatThrownBy(
                        () ->
                                new Message(
                                        Message.Role.USER,
                                        "hi",
                                        List.of(),
                                        List.of(SEARCH),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant");
        assertThatThrownBy(
                        () ->
                                new Message(
                                        Message.Role.ASSISTANT,
                                        "hi",
                                        List.of(),
                                        List.of(),
                                        "call-1",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool message");
        assertThatThrownBy(
                        () ->
                                new Message(
                                        Message.Role.TOOL,
                                        "x",
                                        List.of(),
                                        List.of(),
                                        "call-1",
                                        " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool's name");
        assertThatThrownBy(() -> Message.user(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void copiesItsListsSoCallersCannotChangeThem() {
        List<ToolCall> calls = new ArrayList<>(List.of(SEARCH));
        Message assistant = Message.assistant("", calls);
        calls.clear();
        assertThat(assistant.toolCalls()).containsExactly(SEARCH);
        assertThatThrownBy(() -> assistant.toolCalls().add(SEARCH))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
