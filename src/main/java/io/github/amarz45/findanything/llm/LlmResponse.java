package io.github.amarz45.findanything.llm;

import java.util.List;
import java.util.Objects;

/**
 * A model's reply: its text and the tool calls it asks for (SD03). A reply with tool calls is a
 * step of the agent loop; one without is the final answer.
 *
 * @param text the reply's text; empty when the model only called tools
 * @param toolCalls the tool calls, in the model's order; empty for a final answer
 */
public record LlmResponse(String text, List<ToolCall> toolCalls) {

    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
