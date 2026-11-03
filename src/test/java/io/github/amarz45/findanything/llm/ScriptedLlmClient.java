package io.github.amarz45.findanything.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A test double for {@link LlmClient}: answers each request with the next scripted response (the
 * last one repeats), or fails every call with the scripted exception. Records every request.
 */
public final class ScriptedLlmClient implements LlmClient {

    public final List<LlmRequest> requests = new ArrayList<>();
    private final Deque<LlmResponse> responses = new ArrayDeque<>();
    private final LlmException failure;

    public ScriptedLlmClient(LlmResponse... responses) {
        this.responses.addAll(List.of(responses));
        this.failure = null;
    }

    public ScriptedLlmClient(LlmException failure) {
        this.failure = failure;
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        if (responses.isEmpty()) {
            throw new IllegalStateException("No scripted response left.");
        }
        return responses.size() == 1 ? responses.peek() : responses.poll();
    }

    @Override
    public boolean testConnection() throws LlmException {
        if (failure != null) {
            throw failure;
        }
        return true;
    }
}
