package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelSettingsTest {

    @Test
    void neverPrintsTheKey() {
        ModelSettings settings =
                new ModelSettings(
                        BackendType.CLOUD,
                        "x/y",
                        ModelSettings.OPENROUTER_BASE_URL,
                        "sk-or-secret");
        assertThat(settings.toString())
                .doesNotContain("secret")
                .contains("CLOUD")
                .contains("x/y")
                .contains("apiKey=(set)");
        assertThat(settings.hasApiKey()).isTrue();
    }

    @Test
    void aMissingKeyIsEmptyAndAKeyChangeMakesDifferentSettings() {
        ModelSettings none =
                new ModelSettings(
                        BackendType.LOCAL, "m", ModelSettings.OLLAMA_DEFAULT_BASE_URL, null);
        assertThat(none.apiKey()).isEmpty();
        assertThat(none.hasApiKey()).isFalse();
        assertThat(none.toString()).contains("apiKey=(none)");
        assertThat(none)
                .isEqualTo(
                        new ModelSettings(
                                BackendType.LOCAL,
                                "m",
                                ModelSettings.OLLAMA_DEFAULT_BASE_URL,
                                "  "))
                .isNotEqualTo(
                        new ModelSettings(
                                BackendType.LOCAL,
                                "m",
                                ModelSettings.OLLAMA_DEFAULT_BASE_URL,
                                "k"));
    }
}
