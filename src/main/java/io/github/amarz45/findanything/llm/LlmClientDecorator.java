package io.github.amarz45.findanything.llm;

import java.util.Objects;

/**
 * The base of the privacy decorators (Decorator, F13 and F14): an {@link LlmClient} that holds
 * another one and passes every call on to it. A subclass overrides {@link #complete} to change or
 * record the request on its way to the model. Callers only see the {@link LlmClient} interface, so
 * they can't tell a decorated client from a plain one, and can't skip a decorator.
 */
public abstract class LlmClientDecorator implements LlmClient {

    protected final LlmClient wrappedClient;

    protected LlmClientDecorator(LlmClient wrappedClient) {
        this.wrappedClient = Objects.requireNonNull(wrappedClient, "wrappedClient");
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        return wrappedClient.complete(request);
    }

    @Override
    public boolean testConnection() throws LlmException {
        return wrappedClient.testConnection();
    }
}
