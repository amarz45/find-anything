package io.github.amarz45.findanything.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    @Test
    void returnsTheSameClientForEqualSettings() throws Exception {
        LlmClientFactory factory = new LlmClientFactory();
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
        LlmClientFactory factory = new LlmClientFactory();
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
    void buildsAPlainAdapterForBothBackends() throws Exception {
        LlmClientFactory factory = new LlmClientFactory();
        assertThat(factory.createClient(CLOUD)).isInstanceOf(LangChain4jClient.class);
        assertThat(factory.createClient(LOCAL)).isInstanceOf(LangChain4jClient.class);
        assertThat(factory.createClient(LOCAL)).isNotSameAs(factory.createClient(CLOUD));
    }

    @Test
    void reportsSettingsThatCannotWorkAndCachesNothingForThem() {
        LlmClientFactory factory = new LlmClientFactory();
        ModelSettings noKey =
                new ModelSettings(
                        BackendType.CLOUD, "vendor/model", ModelSettings.OPENROUTER_BASE_URL, "");
        assertThatThrownBy(() -> factory.createClient(noKey))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No OpenRouter API key");
        assertThatThrownBy(() -> factory.createClient(noKey)).isInstanceOf(LlmException.class);
    }

    @Test
    void scriptedDoublesStandInForRealClients() throws Exception {
        ScriptedLlmClient client =
                new ScriptedLlmClient(
                        new LlmResponse("first", List.of()), new LlmResponse("last", List.of()));
        ScriptedLlmClientFactory factory = new ScriptedLlmClientFactory(client);
        assertThat(factory.createClient(LOCAL)).isSameAs(client);
        assertThat(factory.requested).containsExactly(LOCAL);

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
