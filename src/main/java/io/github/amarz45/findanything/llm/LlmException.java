package io.github.amarz45.findanything.llm;

/**
 * A request to a model couldn't be completed: no network, the API refused the key or the request,
 * the model wasn't found, the local runtime isn't running, or the model didn't answer in time
 * (SD03). The message is written for the user, with setup instructions where they help, and never
 * contains an API key.
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
