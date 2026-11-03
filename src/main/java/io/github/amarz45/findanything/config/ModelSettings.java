package io.github.amarz45.findanything.config;

import java.net.URI;
import java.util.Objects;

/**
 * The model a conversation talks to (F13): the backend, the model's name, where to reach it, and
 * the API key for a cloud backend. A {@code Conversation} keeps its settings for life, and {@code
 * LlmClientFactory} caches one client per distinct value, so the key takes part in equality. The
 * key is never printed: {@link #toString()} only says whether one is set.
 *
 * @param backendType cloud or local
 * @param modelName the model's id as the backend knows it (an OpenRouter model id, or an Ollama
 *     model name)
 * @param baseUrl the API's base URL
 * @param apiKey the API key; empty when there is none (always, for a local backend)
 */
public record ModelSettings(BackendType backendType, String modelName, URI baseUrl, String apiKey) {

    /** OpenRouter's OpenAI-compatible API. */
    public static final URI OPENROUTER_BASE_URL = URI.create("https://openrouter.ai/api/v1");

    /** Where Ollama listens unless {@code OLLAMA_BASE_URL} says otherwise. */
    public static final URI OLLAMA_DEFAULT_BASE_URL = URI.create("http://localhost:11434");

    public ModelSettings {
        Objects.requireNonNull(backendType, "backendType");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(baseUrl, "baseUrl");
        apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    /** Describes the settings without revealing the key. */
    @Override
    public String toString() {
        return "ModelSettings[backendType="
                + backendType
                + ", modelName="
                + modelName
                + ", baseUrl="
                + baseUrl
                + ", apiKey="
                + (hasApiKey() ? "(set)" : "(none)")
                + "]";
    }
}
