package io.github.amarz45.findanything.llm;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One message of a conversation as sent to a model: its role, its content, and the source files the
 * content came from, which {@code RedactingLlmClient} uses to withhold local-only content (F14). An
 * assistant message may carry the tool calls the model made, and a tool message carries the id and
 * name of the call it answers, because the provider's API replays both with the history.
 *
 * @param role who the message is from
 * @param content the text; may be empty for an assistant turn that only calls tools
 * @param sourceFiles the indexed files the content quotes from; empty for anything but a tool
 *     result
 * @param toolCalls the tool calls an assistant message made; empty for every other role
 * @param toolCallId for a tool message, the id of the call it answers; null when the provider gave
 *     none, and for every other role
 * @param toolName for a tool message, the name of the tool that was called; null for every other
 *     role
 */
public record Message(
        Role role,
        String content,
        List<Path> sourceFiles,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    /** Who a message is from. */
    public enum Role {
        /** The instructions the system gives the model before the conversation. */
        SYSTEM,
        USER,
        ASSISTANT,
        /** The result of a tool call, which the model reads as file content, not instructions. */
        TOOL
    }

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        sourceFiles = List.copyOf(Objects.requireNonNull(sourceFiles, "sourceFiles"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
            throw new IllegalArgumentException("Only an assistant message can make tool calls.");
        }
        if (role == Role.TOOL) {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("A tool message needs the tool's name.");
            }
        } else if (toolCallId != null || toolName != null) {
            throw new IllegalArgumentException("Only a tool message answers a tool call.");
        }
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, List.of(), List.of(), null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, List.of(), List.of(), null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, List.of(), toolCalls, null, null);
    }

    /** The result of {@code toolCall}, quoting from {@code sourceFiles}. */
    public static Message toolResult(ToolCall toolCall, String content, List<Path> sourceFiles) {
        return new Message(
                Role.TOOL, content, sourceFiles, List.of(), toolCall.id(), toolCall.name());
    }
}
