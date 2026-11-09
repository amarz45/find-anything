package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.LlmClient;
import io.github.amarz45.findanything.llm.LlmException;
import io.github.amarz45.findanything.llm.LlmResponse;
import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.search.SearchResult;
import io.github.amarz45.findanything.storage.IndexedFile;
import io.github.amarz45.findanything.storage.SearchIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the agent loop for one Ask-mode request (F07, F08, F09, F12; SD03): it sends the
 * conversation to the model, carries out the tool calls the model makes through {@link
 * ToolRegistry}, and repeats until the model gives its final answer or the step limit is reached.
 * The final answer is parsed and its citations are verified.
 *
 * <p>This is the client of the Command pattern: it passes each {@link ToolCall} to the registry and
 * never looks at which tool it names.
 *
 * <p>The controller holds no model client. Each call is given the client to use, so a conversation
 * always talks to its own model. {@link #run} blocks until the answer is ready, so it is only
 * called on a background thread.
 */
public final class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** The answer's text when the step limit ends the request. */
    static final String STEP_LIMIT_TEXT =
            "I couldn't finish: I reached the step limit before I had an answer.";

    /** The answer's text when the model sent nothing, even when asked again. */
    static final String EMPTY_REPLY_TEXT = "The model gave no answer.";

    /** The notice on an answer whose citations couldn't be parsed, even when asked again. */
    static final String UNREADABLE_NOTICE =
            "The model's reply isn't in the answer format, so its sources couldn't be checked."
                    + " Treat the answer as unverified.";

    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ResponseParser responseParser;
    private final CitationVerifier citationVerifier;
    private final SearchIndex searchIndex;
    private final int maxSteps;
    private final int maxConversationChars;
    private final Clock clock;

    /**
     * @param searchIndex where the files of an answer are looked up for the results table
     * @param maxSteps how many times the model may be called for one request before the loop stops
     * @param maxConversationChars the most history to send, for {@link Conversation#trimToFit}
     * @param clock the source of today's date for the prompt
     */
    public AgentController(
            ToolRegistry toolRegistry,
            PromptBuilder promptBuilder,
            ResponseParser responseParser,
            CitationVerifier citationVerifier,
            SearchIndex searchIndex,
            int maxSteps,
            int maxConversationChars,
            Clock clock) {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        if (maxConversationChars < 1) {
            throw new IllegalArgumentException("maxConversationChars must be at least 1");
        }
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.responseParser = Objects.requireNonNull(responseParser, "responseParser");
        this.citationVerifier = Objects.requireNonNull(citationVerifier, "citationVerifier");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.maxSteps = maxSteps;
        this.maxConversationChars = maxConversationChars;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Answers one request in a conversation.
     *
     * <p>First the tool calls and results of earlier exchanges are removed from the conversation,
     * leaving their requests and answers. Then the request and everything that follows it are
     * added: each reply that calls tools, each tool result (a rejected call's result is its error,
     * so the model can correct the call), and one closing reply. What comes back is one of:
     *
     * <ul>
     *   <li>the model's answer, with each citation verified and the cited files as {@code
     *       sourceFiles};
     *   <li>when the reply isn't in the answer format, the answer from one reformat request, or, if
     *       that fails too, the reply as it is with no citations and a notice, so {@link
     *       Answer#isVerified()} is false;
     *   <li>when the model is still calling tools after {@code maxSteps} calls, the files the tools
     *       found so far, with a notice.
     * </ul>
     *
     * The answer's files also become the conversation's {@code lastResults}.
     *
     * @param request what the user asked
     * @param conversation the conversation the request belongs to, used by this request only until
     *     the method returns
     * @param llmClient the client for the conversation's own model
     * @param progressListener told each step as it happens, on the calling thread
     * @throws LlmException when the model can't be reached or fails; the conversation keeps the
     *     request and the steps made so far, and the caller falls back to instant search
     * @throws IOException when the index can't be read to check or list the sources
     */
    public Answer run(
            String request,
            Conversation conversation,
            LlmClient llmClient,
            Consumer<String> progressListener)
            throws LlmException, IOException {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(llmClient, "llmClient");
        Objects.requireNonNull(progressListener, "progressListener");
        if (Objects.requireNonNull(request, "request").isBlank()) {
            throw new IllegalArgumentException("The request is empty.");
        }
        LocalDate today = LocalDate.now(clock);
        Set<Path> filesFound = new LinkedHashSet<>();
        // Earlier exchanges keep their request and answer; their tool calls and results go, so a
        // follow-up doesn't resend old search results or lose the whole exchange to trimming.
        conversation.dropToolMessages();
        conversation.addUserMessage(request);

        for (int step = 1; step <= maxSteps; step++) {
            conversation.trimToFit(maxConversationChars);
            List<ToolSpec> toolSpecs = toolRegistry.getToolSpecs();
            progressListener.accept("Step " + step + ": asking the model");
            LlmResponse response =
                    llmClient.complete(promptBuilder.buildRequest(conversation, toolSpecs, today));
            if (!response.hasToolCalls()) {
                return finalAnswer(
                        response,
                        conversation,
                        llmClient,
                        toolSpecs,
                        today,
                        filesFound,
                        progressListener);
            }
            conversation.addAssistantMessage(response);
            for (ToolCall toolCall : response.toolCalls()) {
                progressListener.accept("Step " + step + ": " + describe(toolCall));
                ToolResult toolResult = toolRegistry.execute(toolCall);
                conversation.addToolResult(toolCall, toolResult);
                filesFound.addAll(toolResult.sourceFiles());
                progressListener.accept(
                        "Step " + step + ": " + toolCall.name() + " " + summarize(toolResult));
            }
        }

        // These outcomes reach the user as notices on the answer, so they are logged quietly.
        log.info(
                "The step limit of {} was reached in conversation {}",
                maxSteps,
                conversation.getId());
        progressListener.accept("The step limit of " + maxSteps + " was reached");
        conversation.addAssistantMessage(new LlmResponse(STEP_LIMIT_TEXT, List.of()));
        Answer stopped =
                new Answer(
                        STEP_LIMIT_TEXT,
                        List.of(),
                        List.of(),
                        List.of(
                                "The step limit of "
                                        + maxSteps
                                        + " was reached before the model gave an answer. The"
                                        + " files found so far are listed."));
        return finish(stopped, filesFound, conversation);
    }

    /**
     * Turns the model's final reply into the answer. A reply that can't be parsed is sent back once
     * with what is wrong with it (F09). The conversation gets one closing reply: the one the answer
     * came from.
     */
    private Answer finalAnswer(
            LlmResponse response,
            Conversation conversation,
            LlmClient llmClient,
            List<ToolSpec> toolSpecs,
            LocalDate today,
            Set<Path> filesFound,
            Consumer<String> progressListener)
            throws LlmException, IOException {
        String problems;
        try {
            Answer answer = responseParser.parseAnswer(response);
            conversation.addAssistantMessage(response);
            return finish(answer, citedFiles(answer), conversation);
        } catch (AnswerFormatException e) {
            problems = e.getMessage();
        }

        log.info("The model's reply isn't in the answer format: {}", problems);
        progressListener.accept(
                "The reply isn't in the answer format; asking the model to send it again");
        boolean hasText = !response.text().isBlank();
        // The model has to see the reply it is asked to correct, but the conversation keeps only
        // the reply the answer comes from, so the rejected one goes into a copy.
        Conversation withRejected =
                new Conversation(
                        conversation.getId(),
                        conversation.getModelSettings(),
                        conversation.getMessages(),
                        List.of());
        if (hasText) {
            withRejected.addAssistantMessage(response);
        }
        try {
            LlmResponse second =
                    llmClient.complete(
                            promptBuilder.buildReformatRequest(
                                    withRejected, toolSpecs, today, problems));
            if (second.hasToolCalls()) {
                // It was asked for the same answer in the format, so the calls aren't run.
                log.info("The model answered the reformat request with a tool call");
            } else {
                Answer answer = responseParser.parseAnswer(second);
                conversation.addAssistantMessage(second);
                return finish(answer, citedFiles(answer), conversation);
            }
        } catch (AnswerFormatException e) {
            log.info(
                    "The model's second reply isn't in the answer format either: {}",
                    e.getMessage());
        } catch (LlmException e) {
            if (!hasText) {
                throw e;
            }
            // There is an answer to show, even though its format couldn't be corrected.
            log.warn("The reformat request failed: {}", e.getMessage());
        }

        String text = hasText ? response.text().strip() : EMPTY_REPLY_TEXT;
        conversation.addAssistantMessage(new LlmResponse(text, List.of()));
        Answer unreadable = new Answer(text, List.of(), List.of(), List.of(UNREADABLE_NOTICE));
        return finish(unreadable, filesFound, conversation);
    }

    /**
     * Verifies the answer's citations, lists its files, and records them as the conversation's
     * latest results.
     */
    private Answer finish(Answer answer, Collection<Path> files, Conversation conversation)
            throws IOException {
        Answer verified = citationVerifier.verify(answer);
        List<SearchResult> sourceFiles = new ArrayList<>();
        for (Path file : files) {
            // A file the model made up, or one removed from the index meanwhile, isn't listed.
            Optional<IndexedFile> indexed = searchIndex.getFile(file);
            indexed.ifPresent(found -> sourceFiles.add(new SearchResult(found, null, 0)));
        }
        conversation.setLastResults(sourceFiles);
        return new Answer(verified.text(), verified.citations(), sourceFiles, verified.notices());
    }

    /** The files an answer cites, each once, in the order of the citations. */
    private static Set<Path> citedFiles(Answer answer) {
        Set<Path> files = new LinkedHashSet<>();
        for (Citation citation : answer.citations()) {
            files.add(citation.file());
        }
        return files;
    }

    /** A tool call as a progress line, such as {@code search(query="lease end", limit=5)}. */
    private static String describe(ToolCall toolCall) {
        return toolCall.arguments().entrySet().stream()
                .map(AgentController::describe)
                .collect(Collectors.joining(", ", toolCall.name() + "(", ")"));
    }

    private static String describe(Map.Entry<String, Object> argument) {
        Object value = argument.getValue();
        return argument.getKey() + "=" + (value instanceof String ? "\"" + value + "\"" : value);
    }

    /** A tool result as the end of a progress line: the first line of its content, or its error. */
    private static String summarize(ToolResult toolResult) {
        if (!toolResult.succeeded()) {
            return "failed: " + toolResult.errorMessage();
        }
        String firstLine = toolResult.content().lines().findFirst().orElse("").strip();
        if (firstLine.endsWith(":")) {
            firstLine = firstLine.substring(0, firstLine.length() - 1);
        }
        return "returned: " + firstLine;
    }
}
