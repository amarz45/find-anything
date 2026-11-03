package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import java.net.ConnectException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LangChain4jClientTest {

    static final ModelSettings CLOUD =
            new ModelSettings(
                    BackendType.CLOUD,
                    "vendor/model",
                    ModelSettings.OPENROUTER_BASE_URL,
                    "sk-test");
    static final ModelSettings LOCAL =
            new ModelSettings(
                    BackendType.LOCAL, "llama", ModelSettings.OLLAMA_DEFAULT_BASE_URL, "");

    static final ToolSpec SEARCH =
            new ToolSpec(
                    "search",
                    "Finds files.",
                    List.of(
                            ToolSpec.Parameter.string("query", "Words to look for.", true),
                            ToolSpec.Parameter.oneOf(
                                    "fileType", List.of("pdf", "docx"), "The format.", false),
                            ToolSpec.Parameter.integer("limit", "How many results.", false)));

    @Test
    void convertsMessagesToolSpecsAndToolCallsForTheLibrary() throws Exception {
        FakeChatModel model = new FakeChatModel();
        LangChain4jClient client = new LangChain4jClient(model, CLOUD);
        ToolCall call = new ToolCall("call-1", "search", Map.of("query", "lease"));
        LlmRequest request =
                new LlmRequest(
                        List.of(
                                Message.system("Today is 2026-10-29."),
                                Message.user("When does my lease end?"),
                                Message.assistant("", List.of(call)),
                                Message.toolResult(
                                        call,
                                        "lease.pdf p2: ends August 31, 2026",
                                        List.of(Path.of("lease.pdf"))),
                                Message.assistant("It ends August 31, 2026.", List.of())),
                        List.of(SEARCH),
                        List.of());

        LlmResponse response = client.complete(request);

        assertThat(response.text()).isEqualTo("OK");
        assertThat(response.hasToolCalls()).isFalse();
        ChatRequest sent = model.lastRequest();
        List<ChatMessage> messages = sent.messages();
        assertThat(messages).hasSize(5);
        assertThat(((SystemMessage) messages.get(0)).text()).isEqualTo("Today is 2026-10-29.");
        assertThat(((UserMessage) messages.get(1)).singleText())
                .isEqualTo("When does my lease end?");
        AiMessage assistant = (AiMessage) messages.get(2);
        assertThat(assistant.toolExecutionRequests()).hasSize(1);
        ToolExecutionRequest sentCall = assistant.toolExecutionRequests().get(0);
        assertThat(sentCall.id()).isEqualTo("call-1");
        assertThat(sentCall.name()).isEqualTo("search");
        assertThat(sentCall.arguments()).isEqualTo("{\"query\":\"lease\"}");
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(3);
        assertThat(result.id()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("search");
        assertThat(result.text()).contains("August 31, 2026");
        assertThat(((AiMessage) messages.get(4)).text()).isEqualTo("It ends August 31, 2026.");

        assertThat(sent.toolSpecifications()).hasSize(1);
        ToolSpecification spec = sent.toolSpecifications().get(0);
        assertThat(spec.name()).isEqualTo("search");
        assertThat(spec.description()).isEqualTo("Finds files.");
        assertThat(spec.parameters().properties().keySet())
                .containsExactly("query", "fileType", "limit");
        assertThat(spec.parameters().properties().get("query"))
                .isInstanceOf(JsonStringSchema.class);
        assertThat(spec.parameters().properties().get("fileType"))
                .isInstanceOfSatisfying(
                        JsonEnumSchema.class,
                        e -> assertThat(e.enumValues()).containsExactly("pdf", "docx"));
        assertThat(spec.parameters().properties().get("limit"))
                .isInstanceOf(JsonIntegerSchema.class);
        assertThat(spec.parameters().required()).containsExactly("query");
    }

    @Test
    void sendsNoToolListWhenThereAreNoTools() throws Exception {
        FakeChatModel model = new FakeChatModel();
        new LangChain4jClient(model, LOCAL).complete(new LlmRequest(List.of(Message.user("hi"))));
        assertThat(model.lastRequest().toolSpecifications()).isNullOrEmpty();
    }

    @Test
    void parsesToolCallArgumentsIntoMaps() throws Exception {
        AiMessage reply =
                AiMessage.from(
                        ToolExecutionRequest.builder()
                                .id("c1")
                                .name("search")
                                .arguments(
                                        "{\"query\": \"lease\", \"limit\": 5, \"fileType\": null}")
                                .build(),
                        ToolExecutionRequest.builder().id("c2").name("ping").arguments("").build());
        LangChain4jClient client = new LangChain4jClient(FakeChatModel.replying(reply), CLOUD);

        LlmResponse response = client.complete(new LlmRequest(List.of(Message.user("hi"))));

        assertThat(response.text()).isEmpty();
        assertThat(response.toolCalls()).hasSize(2);
        ToolCall first = response.toolCalls().get(0);
        assertThat(first.id()).isEqualTo("c1");
        assertThat(first.name()).isEqualTo("search");
        assertThat(first.arguments())
                .containsEntry("query", "lease")
                .containsEntry("limit", 5)
                .containsEntry("fileType", null);
        assertThat(response.toolCalls().get(1).arguments()).isEmpty();
    }

    @Test
    void reportsMalformedToolCallArguments() {
        AiMessage reply =
                AiMessage.from(
                        ToolExecutionRequest.builder()
                                .id("c1")
                                .name("search")
                                .arguments("{\"query\": ")
                                .build());
        LangChain4jClient client = new LangChain4jClient(FakeChatModel.replying(reply), CLOUD);
        assertThatThrownBy(() -> client.complete(new LlmRequest(List.of(Message.user("hi")))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("malformed arguments")
                .hasMessageContaining("search");
    }

    @Test
    void mapsAnInvalidApiKeyToAClearMessage() {
        LangChain4jClient client =
                new LangChain4jClient(
                        FakeChatModel.failing(new AuthenticationException("401 Unauthorized")),
                        CLOUD);
        assertThatThrownBy(client::testConnection)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("rejected the API key")
                .hasMessageContaining("OPENROUTER_API_KEY")
                .hasMessageNotContaining("sk-test");
    }

    @Test
    void mapsAnUnreachableLocalRuntimeToSetupInstructions() {
        RuntimeException refused =
                new RuntimeException(
                        new java.io.IOException(new ConnectException("Connection refused")));
        LangChain4jClient client = new LangChain4jClient(FakeChatModel.failing(refused), LOCAL);
        assertThatThrownBy(client::testConnection)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Can't reach Ollama at http://localhost:11434")
                .hasMessageContaining("ollama serve")
                .hasMessageContaining("OLLAMA_BASE_URL");

        LangChain4jClient cloud = new LangChain4jClient(FakeChatModel.failing(refused), CLOUD);
        assertThatThrownBy(cloud::testConnection)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Can't reach OpenRouter")
                .hasMessageContaining("network");
    }

    @Test
    void mapsAnUnknownModel() {
        LangChain4jClient cloud =
                new LangChain4jClient(
                        FakeChatModel.failing(new ModelNotFoundException("404 not found")), CLOUD);
        assertThatThrownBy(cloud::testConnection)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("\"vendor/model\" was not found on OpenRouter")
                .hasMessageContaining("openrouter.ai/models");

        LangChain4jClient local =
                new LangChain4jClient(
                        FakeChatModel.failing(new HttpException(404, "model not found")), LOCAL);
        assertThatThrownBy(local::testConnection)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Ollama has no model \"llama\"")
                .hasMessageContaining("ollama pull llama");
    }

    @Test
    void mapsTimeoutsCreditsAndUnknownErrors() {
        assertThatThrownBy(
                        () ->
                                new LangChain4jClient(
                                                FakeChatModel.failing(
                                                        new TimeoutException("timed out")),
                                                CLOUD)
                                        .testConnection())
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("didn't answer within 60 seconds");
        assertThatThrownBy(
                        () ->
                                new LangChain4jClient(
                                                FakeChatModel.failing(
                                                        new HttpException(
                                                                402, "Insufficient credits")),
                                                CLOUD)
                                        .testConnection())
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no credits");
        assertThatThrownBy(
                        () ->
                                new LangChain4jClient(
                                                FakeChatModel.failing(
                                                        new IllegalStateException("boom")),
                                                LOCAL)
                                        .testConnection())
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("The request to Ollama failed: boom");
    }

    @Test
    void testConnectionSendsAMinimalRequestWithATool() throws Exception {
        FakeChatModel model = new FakeChatModel();
        assertThat(new LangChain4jClient(model, CLOUD).testConnection()).isTrue();
        ChatRequest sent = model.lastRequest();
        assertThat(sent.messages()).hasSize(1);
        assertThat(((UserMessage) sent.messages().get(0)).singleText()).contains("OK");
        assertThat(sent.toolSpecifications())
                .extracting(ToolSpecification::name)
                .containsExactly("ping");

        FakeChatModel silent = FakeChatModel.replying(AiMessage.from(""));
        assertThat(new LangChain4jClient(silent, CLOUD).testConnection()).isFalse();
    }

    @Test
    void refusesSettingsThatCannotWork() {
        assertThatThrownBy(
                        () ->
                                new LangChain4jClient(
                                        new ModelSettings(
                                                BackendType.CLOUD,
                                                "vendor/model",
                                                ModelSettings.OPENROUTER_BASE_URL,
                                                "")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No OpenRouter API key")
                .hasMessageContaining(".env.example");
        assertThatThrownBy(
                        () ->
                                new LangChain4jClient(
                                        new ModelSettings(
                                                BackendType.LOCAL,
                                                " ",
                                                ModelSettings.OLLAMA_DEFAULT_BASE_URL,
                                                "")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No model name")
                .hasMessageContaining("OLLAMA_MODEL");
    }

    @Test
    void buildsRealModelsWithoutTouchingTheNetwork() throws Exception {
        assertThat(new LangChain4jClient(CLOUD).getModelSettings()).isEqualTo(CLOUD);
        assertThat(new LangChain4jClient(LOCAL).getModelSettings()).isEqualTo(LOCAL);
    }
}
