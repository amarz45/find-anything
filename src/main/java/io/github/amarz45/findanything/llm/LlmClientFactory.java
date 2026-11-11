package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.config.BackendType;
import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.Redactor;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the client for a conversation's model (the Factory, F13), caching one client per distinct
 * {@link ModelSettings}. Callers never construct clients themselves, so the decision of which
 * privacy decorators apply is made here alone:
 *
 * <ul>
 *   <li>a cloud backend gets {@link RedactingLlmClient} → {@link LoggingLlmClient} → the adapter,
 *       so local-only content is withheld and sensitive data redacted first, and the log then
 *       records exactly what is sent;
 *   <li>a local backend gets the plain adapter, because nothing leaves the computer.
 * </ul>
 */
public class LlmClientFactory {

    private final Redactor redactor;
    private final FolderPolicy folderPolicy;
    private final PrivacyLog privacyLog;
    private final Clock clock;
    private final Map<ModelSettings, LlmClient> clients = new HashMap<>();

    /**
     * @param redactor replaces sensitive data in cloud requests
     * @param folderPolicy says which files' text must not reach a cloud model
     * @param privacyLog records every cloud request
     * @param clock gives the log entries their time
     */
    public LlmClientFactory(
            Redactor redactor, FolderPolicy folderPolicy, PrivacyLog privacyLog, Clock clock) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.folderPolicy = Objects.requireNonNull(folderPolicy, "folderPolicy");
        this.privacyLog = Objects.requireNonNull(privacyLog, "privacyLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The client for these settings, built on first use: decorated for a cloud backend, plain for a
     * local one.
     *
     * @throws LlmException when the settings can't work (no API key, no model name)
     */
    public synchronized LlmClient createClient(ModelSettings modelSettings) throws LlmException {
        Objects.requireNonNull(modelSettings, "modelSettings");
        LlmClient client = clients.get(modelSettings);
        if (client == null) {
            client = build(modelSettings);
            if (modelSettings.backendType() == BackendType.CLOUD) {
                client =
                        new RedactingLlmClient(
                                new LoggingLlmClient(client, privacyLog, modelSettings, clock),
                                redactor,
                                folderPolicy);
            }
            clients.put(modelSettings, client);
        }
        return client;
    }

    /**
     * Builds the adapter for settings that aren't cached yet. Tests override this to supply
     * scripted clients, which {@link #createClient} decorates like real ones.
     */
    protected LlmClient build(ModelSettings modelSettings) throws LlmException {
        return new LangChain4jClient(modelSettings);
    }
}
