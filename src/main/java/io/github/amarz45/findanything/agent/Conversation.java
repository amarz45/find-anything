package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.Message;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.search.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One Ask-mode conversation (F12): the requests, the model's replies and tool calls, and the tool
 * results, in order, so a follow-up such as "only the ones from 2025" can build on what came
 * before.
 *
 * <p>A conversation is bound to one model for its whole life: its {@code modelSettings} are set
 * when it is created and never change, so text never moves from a local model's conversation to a
 * cloud model. It is used by one request at a time (the GUI disables Ask input while a request
 * runs); the methods are synchronized so the UI thread can read the messages meanwhile.
 */
public final class Conversation {

    /** What a failed tool call's message starts with, so the model sees it must correct itself. */
    static final String ERROR_PREFIX = "Error: ";

    private final String id;
    private final ModelSettings modelSettings;
    private final List<Message> messages = new ArrayList<>();
    private List<SearchResult> lastResults = List.of();

    /** Starts an empty conversation with the model it will always use. */
    public Conversation(String id, ModelSettings modelSettings) {
        this(id, modelSettings, List.of(), List.of());
    }

    /**
     * Restores a saved conversation.
     *
     * @param id the conversation's id, which names its saved file
     * @param modelSettings the model the conversation was started with
     * @param messages the history so far, oldest first
     * @param lastResults the files of the latest answer
     */
    public Conversation(
            String id,
            ModelSettings modelSettings,
            List<Message> messages,
            List<SearchResult> lastResults) {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("A conversation needs an id.");
        }
        this.id = id;
        this.modelSettings = Objects.requireNonNull(modelSettings, "modelSettings");
        this.messages.addAll(Objects.requireNonNull(messages, "messages"));
        this.lastResults = List.copyOf(Objects.requireNonNull(lastResults, "lastResults"));
    }

    /** Adds the user's request, which starts a new exchange. */
    public synchronized void addUserMessage(String text) {
        messages.add(Message.user(Objects.requireNonNull(text, "text")));
    }

    /** Adds the model's reply: its text and any tool calls it made. */
    public synchronized void addAssistantMessage(LlmResponse response) {
        Objects.requireNonNull(response, "response");
        messages.add(Message.assistant(response.text(), response.toolCalls()));
    }

    /**
     * Adds what a tool call produced, as the message that answers the call. A failed call's message
     * is its error, so the model can correct the call (F07).
     */
    public synchronized void addToolResult(ToolCall toolCall, ToolResult toolResult) {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(toolResult, "toolResult");
        String content =
                toolResult.succeeded()
                        ? toolResult.content()
                        : ERROR_PREFIX + toolResult.errorMessage();
        messages.add(Message.toolResult(toolCall, content, toolResult.sourceFiles()));
    }

    /**
     * Keeps the history within the model's context by dropping the oldest exchanges until the
     * messages hold at most {@code maxChars} characters. An exchange is a request and everything up
     * to the next request, and is dropped whole, so a tool result never loses the call it answers.
     * The latest exchange is always kept, even when it alone is longer.
     */
    public synchronized void trimToFit(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be at least 1");
        }
        int total = messages.stream().mapToInt(Conversation::lengthOf).sum();
        while (total > maxChars) {
            int nextExchange = startOfSecondExchange();
            if (nextExchange < 0) {
                return;
            }
            List<Message> oldest = messages.subList(0, nextExchange);
            total -= oldest.stream().mapToInt(Conversation::lengthOf).sum();
            oldest.clear();
        }
    }

    /** The index of the second user message, or -1 when there is only one exchange. */
    private int startOfSecondExchange() {
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i).role() == Message.Role.USER) {
                return i;
            }
        }
        return -1;
    }

    /** A message's size as sent to the model: its text plus its tool calls. */
    private static int lengthOf(Message message) {
        int length = message.content().length();
        for (ToolCall toolCall : message.toolCalls()) {
            length += toolCall.name().length() + toolCall.arguments().toString().length();
        }
        return length;
    }

    /** The history, oldest first, as a copy. */
    public synchronized List<Message> getMessages() {
        return List.copyOf(messages);
    }

    /** The model this conversation was started with and always uses. */
    public ModelSettings getModelSettings() {
        return modelSettings;
    }

    public String getId() {
        return id;
    }

    /** The files of the latest answer, which a follow-up such as "the second one" refers to. */
    public synchronized List<SearchResult> getLastResults() {
        return lastResults;
    }

    public synchronized void setLastResults(List<SearchResult> lastResults) {
        this.lastResults = List.copyOf(Objects.requireNonNull(lastResults, "lastResults"));
    }
}
