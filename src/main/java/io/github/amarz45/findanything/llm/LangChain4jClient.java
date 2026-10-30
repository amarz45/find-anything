package io.github.amarz45.findanything.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts a LangChain4j {@code ChatModel} to {@link LlmClient} (the Adapter pattern, F13): an
 * OpenAI-compatible model for the cloud, reached through OpenRouter, or an Ollama model for the
 * local backend. It converts an {@link LlmRequest}'s messages and tool specs to the library's types
 * and the reply back to an {@link LlmResponse}, and turns the library's errors into {@link
 * LlmException}s written for the user. The rest of the system never sees the library's types. Keeps
 * no state between calls, so it is thread-safe.
 */
public final class LangChain4jClient implements LlmClient {

    /** Cloud models answer in seconds; the limit covers a slow provider. */
    static final Duration CLOUD_TIMEOUT = Duration.ofSeconds(60);

    /** A local model may first have to be loaded into memory, over Tailscale. */
    static final Duration LOCAL_TIMEOUT = Duration.ofSeconds(180);

    /** The context window asked of Ollama, which otherwise defaults to a small one. */
    static final int OLLAMA_NUM_CTX = 16_384;

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT =
            new TypeReference<>() {};

    private final ChatModel chatModel;
    private final ModelSettings modelSettings;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Builds the client for the given settings.
     *
     * @throws LlmException when the settings can't work: a cloud backend without an API key, or no
     *     model name
     */
    public LangChain4jClient(ModelSettings modelSettings) throws LlmException {
        this(buildChatModel(modelSettings), modelSettings);
    }

    /** For tests: wraps a scripted chat model. */
    LangChain4jClient(ChatModel chatModel, ModelSettings modelSettings) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.modelSettings = Objects.requireNonNull(modelSettings, "modelSettings");
    }

    private static ChatModel buildChatModel(ModelSettings settings) throws LlmException {
        Objects.requireNonNull(settings, "modelSettings");
        if (settings.modelName().isBlank()) {
            throw new LlmException(
                    "No model name. Pass --model, or set "
                            + (settings.backendType() == BackendType.CLOUD
                                    ? "OPENROUTER_MODEL"
                                    : "OLLAMA_MODEL")
                            + " in .env.");
        }
        return switch (settings.backendType()) {
            case CLOUD -> {
                if (!settings.hasApiKey()) {
                    throw new LlmException(
                            "No OpenRouter API key. Copy .env.example to .env and set"
                                    + " OPENROUTER_API_KEY (https://openrouter.ai/keys).");
                }
                yield OpenAiChatModel.builder()
                        .baseUrl(settings.baseUrl().toString())
                        .apiKey(settings.apiKey())
                        .modelName(settings.modelName())
                        .timeout(CLOUD_TIMEOUT)
                        .maxRetries(2)
                        // OpenRouter's per-request switch: no providers that train on prompts.
                        .customParameters(Map.of("provider", Map.of("data_collection", "deny")))
                        // Request logging would print the Authorization header.
                        .logRequests(false)
                        .logResponses(false)
                        .build();
            }
            case LOCAL ->
                    OllamaChatModel.builder()
                            .baseUrl(settings.baseUrl().toString())
                            .modelName(settings.modelName())
                            .timeout(LOCAL_TIMEOUT)
                            .numCtx(OLLAMA_NUM_CTX)
                            .maxRetries(1)
                            .build();
        };
    }

    public ModelSettings getModelSettings() {
        return modelSettings;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        ChatRequest.Builder builder = ChatRequest.builder().messages(toChatMessages(request));
        if (!request.toolSpecs().isEmpty()) {
            builder.toolSpecifications(
                    request.toolSpecs().stream().map(this::toToolSpecification).toList());
        }
        ChatResponse response;
        try {
            response = chatModel.chat(builder.build());
        } catch (RuntimeException e) {
            throw toLlmException(e);
        }
        return toLlmResponse(response);
    }

    @Override
    public boolean testConnection() throws LlmException {
        // The tool makes the test catch a model that can't call tools, which the agent needs.
        ToolSpec ping = new ToolSpec("ping", "Does nothing. Never call it.", List.of());
        LlmResponse response =
                complete(
                        new LlmRequest(
                                List.of(Message.user("Reply with the single word OK.")),
                                List.of(ping),
                                List.of()));
        return !response.text().isBlank() || response.hasToolCalls();
    }

    // ------------------------------------------------------------------ to the library's types

    private List<ChatMessage> toChatMessages(LlmRequest request) throws LlmException {
        List<ChatMessage> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            messages.add(
                    switch (message.role()) {
                        case SYSTEM -> SystemMessage.from(message.content());
                        case USER -> UserMessage.from(message.content());
                        case ASSISTANT -> toAiMessage(message);
                        case TOOL ->
                                ToolExecutionResultMessage.from(
                                        message.toolCallId(),
                                        message.toolName(),
                                        message.content());
                    });
        }
        return messages;
    }

    private AiMessage toAiMessage(Message message) throws LlmException {
        if (message.toolCalls().isEmpty()) {
            return AiMessage.from(message.content());
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (ToolCall toolCall : message.toolCalls()) {
            requests.add(
                    ToolExecutionRequest.builder()
                            .id(toolCall.id())
                            .name(toolCall.name())
                            .arguments(toJson(toolCall.arguments()))
                            .build());
        }
        return message.content().isBlank()
                ? AiMessage.from(requests)
                : AiMessage.from(message.content(), requests);
    }

    private String toJson(Map<String, Object> arguments) throws LlmException {
        try {
            return mapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new LlmException("A tool call's arguments can't be written as JSON.", e);
        }
    }

    private ToolSpecification toToolSpecification(ToolSpec spec) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        for (ToolSpec.Parameter parameter : spec.parameters()) {
            switch (parameter.type()) {
                case STRING -> {
                    if (parameter.allowedValues().isEmpty()) {
                        schema.addStringProperty(parameter.name(), parameter.description());
                    } else {
                        schema.addEnumProperty(
                                parameter.name(),
                                parameter.allowedValues(),
                                parameter.description());
                    }
                }
                case INTEGER ->
                        schema.addIntegerProperty(parameter.name(), parameter.description());
                case BOOLEAN ->
                        schema.addBooleanProperty(parameter.name(), parameter.description());
            }
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        schema.required(required);
        return ToolSpecification.builder()
                .name(spec.name())
                .description(spec.description())
                .parameters(schema.build())
                .build();
    }

    // ------------------------------------------------------------------ from the library's types

    private LlmResponse toLlmResponse(ChatResponse response) throws LlmException {
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null) {
            return new LlmResponse("", List.of());
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        if (aiMessage.hasToolExecutionRequests()) {
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                toolCalls.add(new ToolCall(request.id(), request.name(), parseArguments(request)));
            }
        }
        return new LlmResponse(aiMessage.text(), toolCalls);
    }

    private Map<String, Object> parseArguments(ToolExecutionRequest request) throws LlmException {
        String json = request.arguments();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> arguments = mapper.readValue(json, JSON_OBJECT);
            return arguments == null ? Map.of() : arguments;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new LlmException(
                    "The model returned a tool call with malformed arguments ("
                            + request.name()
                            + "): "
                            + e.getMessage(),
                    e);
        }
    }

    // ------------------------------------------------------------------ errors

    /** Turns a library error into one the user can act on. Messages never contain the key. */
    private LlmException toLlmException(RuntimeException e) {
        boolean cloud = modelSettings.backendType() == BackendType.CLOUD;
        String backend = cloud ? "OpenRouter" : "Ollama";
        String name = modelSettings.modelName();
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof AuthenticationException) {
            return new LlmException(
                    "OpenRouter rejected the API key. Check OPENROUTER_API_KEY in .env. ("
                            + detail
                            + ")",
                    e);
        }
        if (e instanceof ModelNotFoundException
                || (e instanceof HttpException http && http.statusCode() == 404)) {
            return new LlmException(
                    cloud
                            ? "The model \""
                                    + name
                                    + "\" was not found on OpenRouter, or no provider"
                                    + " offers it with tool calling. Copy its exact ID from"
                                    + " https://openrouter.ai/models. ("
                                    + detail
                                    + ")"
                            : "Ollama has no model \""
                                    + name
                                    + "\". Run: ollama pull "
                                    + name
                                    + " ("
                                    + detail
                                    + ")",
                    e);
        }
        if (e instanceof UnresolvedModelServerException || isConnectionFailure(e)) {
            return new LlmException(
                    cloud
                            ? "Can't reach OpenRouter at "
                                    + modelSettings.baseUrl()
                                    + ". Check your network connection. ("
                                    + detail
                                    + ")"
                            : "Can't reach Ollama at "
                                    + modelSettings.baseUrl()
                                    + ". Start it with \"ollama serve\" (install it from"
                                    + " https://ollama.com), or set OLLAMA_BASE_URL in .env. ("
                                    + detail
                                    + ")",
                    e);
        }
        if (e instanceof TimeoutException) {
            Duration timeout = cloud ? CLOUD_TIMEOUT : LOCAL_TIMEOUT;
            return new LlmException(
                    "The model didn't answer within " + timeout.toSeconds() + " seconds.", e);
        }
        if (e instanceof RateLimitException) {
            return new LlmException(
                    backend + " is rate-limiting requests; try again shortly. (" + detail + ")", e);
        }
        if (e instanceof HttpException http && http.statusCode() == 402) {
            return new LlmException(
                    "The OpenRouter account has no credits left (https://openrouter.ai/credits).",
                    e);
        }
        if (e instanceof InvalidRequestException) {
            return new LlmException(backend + " rejected the request: " + detail, e);
        }
        return new LlmException("The request to " + backend + " failed: " + detail, e);
    }

    private static boolean isConnectionFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
