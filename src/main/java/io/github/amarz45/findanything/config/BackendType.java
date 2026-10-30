package io.github.amarz45.findanything.config;

/** Where a conversation's model runs (F13). */
public enum BackendType {
    /** A cloud model, reached through OpenRouter; requests pass the privacy decorators. */
    CLOUD,
    /** A local model run by Ollama; nothing leaves the computer. */
    LOCAL
}
