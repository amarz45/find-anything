package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmRequestTest {

    static final ToolSpec SEARCH =
            new ToolSpec(
                    "search",
                    "Finds files.",
                    List.of(
                            ToolSpec.Parameter.string("query", "Words to look for.", true),
                            ToolSpec.Parameter.integer("limit", "How many results.", false)));

    @Test
    void withMessagesKeepsTheToolsAndTheRedactedCategories() {
        LlmRequest request =
                new LlmRequest(List.of(Message.user("hi")), List.of(SEARCH), List.of("card"));
        LlmRequest redacted = request.withMessages(List.of(Message.user("[redacted]")));
        assertThat(redacted.messages()).containsExactly(Message.user("[redacted]"));
        assertThat(redacted.toolSpecs()).containsExactly(SEARCH);
        assertThat(redacted.redactedCategories()).containsExactly("card");
        assertThat(request.messages()).containsExactly(Message.user("hi"));
    }

    @Test
    void aPlainRequestHasNoToolsAndNothingRedacted() {
        LlmRequest request = new LlmRequest(List.of(Message.user("hi")));
        assertThat(request.toolSpecs()).isEmpty();
        assertThat(request.redactedCategories()).isEmpty();
    }

    @Test
    void aResponseWithToolCallsIsNotAFinalAnswer() {
        ToolCall call = new ToolCall("1", "search", Map.of("query", "lease"));
        assertThat(new LlmResponse(null, List.of(call)).hasToolCalls()).isTrue();
        assertThat(new LlmResponse(null, List.of(call)).text()).isEmpty();
        assertThat(new LlmResponse("Your lease ends August 31, 2026.", List.of()).hasToolCalls())
                .isFalse();
    }

    @Test
    void aToolCallKeepsNullArgumentValuesAndCannotBeChanged() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "lease");
        arguments.put("fileType", null);
        ToolCall call = new ToolCall("1", "search", arguments);
        arguments.put("query", "changed");
        assertThat(call.arguments())
                .containsEntry("query", "lease")
                .containsEntry("fileType", null);
        assertThatThrownBy(() -> call.arguments().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ToolCall("1", " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aToolSpecRejectsRepeatedParametersAndChoicesOnNonStrings() {
        ToolSpec.Parameter query = ToolSpec.Parameter.string("query", "Words.", true);
        assertThatThrownBy(() -> new ToolSpec("search", "Finds files.", List.of(query, query)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats");
        assertThatThrownBy(
                        () ->
                                new ToolSpec.Parameter(
                                        "limit",
                                        ToolSpec.Type.INTEGER,
                                        "How many.",
                                        false,
                                        List.of("1")))
                .isInstanceOf(IllegalArgumentException.class);
        ToolSpec.Parameter type =
                ToolSpec.Parameter.oneOf("fileType", List.of("pdf", "docx"), "The format.", false);
        assertThat(type.allowedValues()).containsExactly("pdf", "docx");
        assertThat(type.type()).isEqualTo(ToolSpec.Type.STRING);
        assertThatThrownBy(() -> ToolSpec.Parameter.oneOf("t", List.of(), "", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
