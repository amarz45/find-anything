package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.config.ModelSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the client for a conversation's model (the Factory, F13), caching one client per distinct
 * {@link ModelSettings}. Callers never construct clients themselves, so the decision of which
 * privacy decorators apply is made here alone: a cloud backend will get the redacting and logging
 * chain (Slice 3), a local backend a plain {@link LangChain4jClient}.
 */
public class LlmClientFactory {

    private final Map<ModelSettings, LlmClient> clients = new HashMap<>();

    /**
     * The client for these settings, built on first use.
     *
     * @throws LlmException when the settings can't work (no API key, no model name)
     */
    public synchronized LlmClient createClient(ModelSettings modelSettings) throws LlmException {
        Objects.requireNonNull(modelSettings, "modelSettings");
        LlmClient client = clients.get(modelSettings);
        if (client == null) {
            client = build(modelSettings);
            clients.put(modelSettings, client);
        }
        return client;
    }

    /** Builds a client that isn't cached yet; tests override this to supply scripted clients. */
    protected LlmClient build(ModelSettings modelSettings) throws LlmException {
        return new LangChain4jClient(modelSettings);
    }
}
