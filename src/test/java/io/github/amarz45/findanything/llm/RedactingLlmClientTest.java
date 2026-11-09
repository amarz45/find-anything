package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.Redactor;
import io.github.amarz45.findanything.privacy.SensitivePattern;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedactingLlmClientTest {

    static final Path DOCS = Path.of("docs").toAbsolutePath();
    static final Path BANK = DOCS.resolve("private/bank-details.txt");
    static final Path NOTES = DOCS.resolve("notes.txt");
    static final String CARD = "4111 1111 1111 1111";
    static final String SIN = "046 454 286";
    static final String API_KEY = "sk-test-FAKE-1234567890abcdef";

    ScriptedLlmClient inner;
    Redactor redactor;
    FolderPolicy folderPolicy;
    RedactingLlmClient client;

    @BeforeEach
    void setUp() {
        inner = new ScriptedLlmClient(new LlmResponse("OK", List.of()));
        redactor = new Redactor(SensitivePattern.builtIn());
        folderPolicy = new FolderPolicy();
        client = new RedactingLlmClient(inner, redactor, folderPolicy);
    }

    static Message toolResult(String id, String content, Path... sourceFiles) {
        return Message.toolResult(
                new ToolCall(id, "read_excerpt", Map.of()), content, List.of(sourceFiles));
    }

    @Test
    void redactsSensitiveDataInEveryMessageAndReportsTheCategories() throws Exception {
        Message system = Message.system("Answer from the user's files.");
        Message user = Message.user("Is my card " + CARD + " in a file?");
        Message assistant =
                Message.assistant(
                        "", List.of(new ToolCall("c1", "search", Map.of("query", "card"))));
        Message tool = toolResult("c1", "SIN: " + SIN + "\nCard: " + CARD, NOTES);
        ToolSpec spec = new ToolSpec("search", "Searches.", List.of());
        LlmRequest request =
                new LlmRequest(List.of(system, user, assistant, tool), List.of(spec), List.of());

        LlmResponse response = client.complete(request);

        assertThat(response.text()).isEqualTo("OK");
        LlmRequest sent = inner.requests.get(0);
        assertThat(sent.messages()).hasSize(4);
        // Messages with nothing to redact are passed on as they are.
        assertThat(sent.messages().get(0)).isSameAs(system);
        assertThat(sent.messages().get(2)).isSameAs(assistant);
        assertThat(sent.messages().get(1).content())
                .isEqualTo("Is my card [redacted: card-number] in a file?");
        Message sentTool = sent.messages().get(3);
        assertThat(sentTool.content())
                .isEqualTo("SIN: [redacted: sin]\nCard: [redacted: card-number]");
        assertThat(sentTool.role()).isEqualTo(Message.Role.TOOL);
        assertThat(sentTool.sourceFiles()).containsExactly(NOTES);
        assertThat(sentTool.toolCallId()).isEqualTo("c1");
        assertThat(sentTool.toolName()).isEqualTo("read_excerpt");
        assertThat(sent.toolSpecs()).containsExactly(spec);
        assertThat(sent.redactedCategories()).containsExactly("card-number", "sin");
        // The caller's request is left as it was.
        assertThat(request.messages().get(1).content()).contains(CARD);
        assertThat(request.redactedCategories()).isEmpty();
    }

    @Test
    void withholdsAMessageWholeWhenAnyOfItsSourcesIsLocalOnly() throws Exception {
        folderPolicy.setLocalOnly(DOCS.resolve("private"), true);
        Message mixed =
                toolResult(
                        "c1",
                        "1. " + NOTES + "\nDentist on March 4.\n2. " + BANK + "\nKey: " + API_KEY,
                        NOTES,
                        BANK);
        Message other = toolResult("c2", "Dentist on March 4.", NOTES);

        client.complete(new LlmRequest(List.of(Message.user("What's in my files?"), mixed, other)));

        LlmRequest sent = inner.requests.get(0);
        Message withheld = sent.messages().get(1);
        assertThat(withheld.content()).isEqualTo(RedactingLlmClient.WITHHELD);
        assertThat(withheld.content()).isEqualTo("[withheld: local-only source]");
        assertThat(withheld.sourceFiles()).containsExactly(NOTES, BANK);
        assertThat(withheld.toolCallId()).isEqualTo("c1");
        assertThat(sent.messages().get(2)).isSameAs(other);
        // A withheld message isn't scanned, so its key isn't counted as a redaction.
        assertThat(sent.redactedCategories()).isEmpty();
    }

    @Test
    void followsThePolicyAndTheTogglesAsTheyAreAtEachRequest() throws Exception {
        LlmRequest request =
                new LlmRequest(List.of(toolResult("c1", "SIN: " + SIN + " key " + API_KEY, BANK)));

        redactor.setCategoryEnabled("sin", false);
        client.complete(request);
        assertThat(inner.requests.get(0).messages().get(0).content())
                .isEqualTo("SIN: " + SIN + " key [redacted: api-key]");
        assertThat(inner.requests.get(0).redactedCategories()).containsExactly("api-key");

        folderPolicy.setLocalOnly(DOCS.resolve("private"), true);
        client.complete(request);
        assertThat(inner.requests.get(1).messages().get(0).content())
                .isEqualTo(RedactingLlmClient.WITHHELD);

        folderPolicy.setLocalOnly(DOCS.resolve("private"), false);
        redactor.setCategoryEnabled("sin", true);
        client.complete(request);
        assertThat(inner.requests.get(2).messages().get(0).content())
                .isEqualTo("SIN: [redacted: sin] key [redacted: api-key]");
    }

    @Test
    void keepsCategoriesAlreadyOnTheRequestAndPassesFailuresOn() throws Exception {
        client.complete(
                new LlmRequest(
                        List.of(Message.user("key " + API_KEY)),
                        List.of(),
                        List.of("card-number")));
        assertThat(inner.requests.get(0).redactedCategories())
                .containsExactly("card-number", "api-key");

        RedactingLlmClient failing =
                new RedactingLlmClient(
                        new ScriptedLlmClient(new LlmException("Can't reach OpenRouter.")),
                        redactor,
                        folderPolicy);
        assertThatThrownBy(() -> failing.complete(new LlmRequest(List.of(Message.user("hi")))))
                .isInstanceOf(LlmException.class)
                .hasMessage("Can't reach OpenRouter.");
        assertThatThrownBy(failing::testConnection).hasMessage("Can't reach OpenRouter.");
        assertThat(client.testConnection()).isTrue();
    }
}
