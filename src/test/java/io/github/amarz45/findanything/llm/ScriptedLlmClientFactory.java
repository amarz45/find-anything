package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.config.ModelSettings;
import java.util.ArrayList;
import java.util.List;

/** A factory that hands out one scripted client instead of building real ones. */
public final class ScriptedLlmClientFactory extends LlmClientFactory {

    public final List<ModelSettings> requested = new ArrayList<>();
    private final LlmClient client;
    private final LlmException buildFailure;

    public ScriptedLlmClientFactory(LlmClient client) {
        this.client = client;
        this.buildFailure = null;
    }

    /** A factory whose settings can't work, like a cloud backend without a key. */
    public ScriptedLlmClientFactory(LlmException buildFailure) {
        this.client = null;
        this.buildFailure = buildFailure;
    }

    @Override
    protected LlmClient build(ModelSettings modelSettings) throws LlmException {
        requested.add(modelSettings);
        if (buildFailure != null) {
            throw buildFailure;
        }
        return client;
    }
}
