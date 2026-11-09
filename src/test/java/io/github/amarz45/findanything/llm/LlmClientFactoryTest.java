package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.Redactor;
import io.github.amarz45.findanything.privacy.SensitivePattern;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmClientFactoryTest {

    static final ModelSettings CLOUD =
            new ModelSettings(
                    BackendType.CLOUD,
                    "vendor/model",
                    ModelSettings.OPENROUTER_BASE_URL,
                    "sk-test");
    static final ModelSettings LOCAL =
            new ModelSettings(
                    BackendType.LOCAL, "llama", ModelSettings.OLLAMA_DEFAULT_BASE_URL, "");

    @TempDir Path home;

    LlmClientFactory factory() {
        return new LlmClientFactory(
                new Redactor(SensitivePattern.builtIn()),
                new FolderPolicy(),
                new PrivacyLog(home.resolve("privacy-log.jsonl")),
                Clock.systemUTC());
    }

    @Test
    void returnsTheSameClientForEqualSettings() throws Exception {
        LlmClientFactory factory = factory();
        LlmClient first = factory.createClient(CLOUD);
        LlmClient again =
                factory.createClient(
                        new ModelSettings(
                                BackendType.CLOUD,
                                "vendor/model",
                                ModelSettings.OPENROUTER_BASE_URL,
                                "sk-test"));
        assertThat(again).isSameAs(first);
    }

    @Test
    void returnsANewClientWhenTheModelOrTheKeyChanges() throws Exception {
        LlmClientFactory factory = factory();
        LlmClient first = factory.createClient(CLOUD);
        assertThat(
                        factory.createClient(
                                new ModelSettings(
                                        BackendType.CLOUD,
                                        "vendor/other",
                                        ModelSettings.OPENROUTER_BASE_URL,
                                        "sk-test")))
                .isNotSameAs(first);
        assertThat(
                        factory.createClient(
                                new ModelSettings(
                                        BackendType.CLOUD,
                                        "vendor/model",
                                        ModelSettings.OPENROUTER_BASE_URL,
                                        "sk-new")))
                .isNotSameAs(first);
    }

    @Test
    void wrapsACloudClientInTheDecoratorsAndLeavesALocalOnePlain() throws Exception {
        LlmClientFactory factory = factory();

        // Redacting outside, logging inside it, then the adapter: the log records what is sent.
        LlmClient cloud = factory.createClient(CLOUD);
        assertThat(cloud).isInstanceOf(RedactingLlmClient.class);
        LlmClient inside = ((RedactingLlmClient) cloud).wrappedClient;
        assertThat(inside).isInstanceOf(LoggingLlmClient.class);
        assertThat(((LoggingLlmClient) inside).wrappedClient).isInstanceOf(LangChain4jClient.class);

        LlmClient local = factory.createClient(LOCAL);
        assertThat(local).isInstanceOf(LangChain4jClient.class);
    }

    @Test
    void reportsSettingsThatCannotWorkAndCachesNothingForThem() {
        LlmClientFactory factory = factory();
        ModelSettings noKey =
                new ModelSettings(
                        BackendType.CLOUD, "vendor/model", ModelSettings.OPENROUTER_BASE_URL, "");
        assertThatThrownBy(() -> factory.createClient(noKey))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No OpenRouter API key");
        assertThatThrownBy(() -> factory.createClient(noKey)).isInstanceOf(LlmException.class);
    }

    @Test
    void aCloudRequestIsRedactedThenLoggedThenSentAndALocalOneIsSentAsItIs() throws Exception {
        ScriptedLlmClient client = new ScriptedLlmClient(new LlmResponse("OK", List.of()));
        ScriptedLlmClientFactory factory = new ScriptedLlmClientFactory(client);
        LlmRequest request =
                new LlmRequest(List.of(Message.user("My card is 4111 1111 1111 1111.")));

        factory.createClient(CLOUD).complete(request);
        assertThat(client.requests.get(0).messages().get(0).content())
                .isEqualTo("My card is [redacted: card-number].");
        assertThat(factory.privacyLog.getEntries(10)).hasSize(1);
        assertThat(factory.privacyLog.getEntries(10).get(0).excerpts().get(0).text())
                .isEqualTo("My card is [redacted: card-number].");
        assertThat(factory.privacyLog.getEntries(10).get(0).redactedCategories())
                .containsExactly("card-number");
        assertThat(factory.privacyLog.getEntries(10).get(0).modelName()).isEqualTo("vendor/model");

        // Nothing leaves the computer with a local model, so nothing is redacted or logged.
        factory.createClient(LOCAL).complete(request);
        assertThat(client.requests.get(1)).isSameAs(request);
        assertThat(factory.privacyLog.getEntries(10)).hasSize(1);
    }

    @Test
    void scriptedDoublesStandInForRealClients() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        new LlmResponse("first", List.of()), new LlmResponse("last", List.of()));
        ScriptedLlmClientFactory factory = new ScriptedLlmClientFactory(client);
        assertThat(factory.createClient(LOCAL)).isSameAs(client);
        assertThat(factory.requested).containsExactly(LOCAL);
        // For a cloud model the scripted client is decorated like a real one.
        assertThat(factory.createClient(CLOUD)).isInstanceOf(RedactingLlmClient.class);

        LlmRequest request = new LlmRequest(List.of(Message.user("hi")));
        assertThat(client.complete(request).text()).isEqualTo("first");
        assertThat(client.complete(request).text()).isEqualTo("last");
        assertThat(client.complete(request).text()).isEqualTo("last");
        assertThat(client.requests).hasSize(3);
        assertThat(client.testConnection()).isTrue();

        ScriptedLlmClient failing = new ScriptedLlmClient(new LlmException("Can't reach Ollama"));
        assertThatThrownBy(failing::testConnection).hasMessage("Can't reach Ollama");
        assertThatThrownBy(() -> failing.complete(request)).hasMessage("Can't reach Ollama");
        assertThatThrownBy(
                        () ->
                                new ScriptedLlmClientFactory(new LlmException("No key"))
                                        .createClient(CLOUD))
                .hasMessage("No key");
    }
}
