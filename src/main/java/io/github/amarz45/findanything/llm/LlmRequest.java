package io.github.amarz45.findanything.llm;

import java.util.List;
import java.util.Objects;

/**
 * Everything a model needs for one completion: the conversation so far and the tools it may call
 * (built by {@code PromptBuilder}). {@code redactedCategories} records which sensitive-data
 * categories were redacted from the messages, for the privacy log (F14); it is empty until a {@code
 * RedactingLlmClient} fills it.
 *
 * @param messages the messages in order, starting with the system message
 * @param toolSpecs the tools the model may call; empty when it may call none
 * @param redactedCategories the categories redacted from {@code messages}
 */
public record LlmRequest(
        List<Message> messages, List<ToolSpec> toolSpecs, List<String> redactedCategories) {

    public LlmRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        toolSpecs = List.copyOf(Objects.requireNonNull(toolSpecs, "toolSpecs"));
        redactedCategories =
                List.copyOf(Objects.requireNonNull(redactedCategories, "redactedCategories"));
    }

    /** A request with no tools and nothing redacted. */
    public LlmRequest(List<Message> messages) {
        this(messages, List.of(), List.of());
    }

    /** The same request with other messages, keeping the tools and the redacted categories. */
    public LlmRequest withMessages(List<Message> messages) {
        return new LlmRequest(messages, toolSpecs, redactedCategories);
    }
}
