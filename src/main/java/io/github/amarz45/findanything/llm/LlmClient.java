package io.github.amarz45.findanything.llm;

/**
 * A language model, in the system's own terms (the Adapter pattern's target, F13). The agent only
 * sees this interface, so the provider can change without touching it, and tests can script
 * replies. Implementations keep no state between calls and may be called from any thread.
 */
public interface LlmClient {

    /**
     * Sends the request and returns the model's reply.
     *
     * @throws LlmException when the model is unavailable or refuses the request; the message says
     *     why in the user's terms
     */
    LlmResponse complete(LlmRequest request) throws LlmException;

    /**
     * Sends a minimal request to check that the backend answers (UC08).
     *
     * @return true when the backend answered; false when it was reached but sent nothing back
     * @throws LlmException when it can't be reached, refuses the key, or lacks the model; the
     *     message says what to fix
     */
    boolean testConnection() throws LlmException;
}
