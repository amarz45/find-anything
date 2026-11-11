package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.Redactor;
import io.github.amarz45.findanything.privacy.SensitivePattern;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * A factory that hands out one scripted client instead of building real ones. For a cloud model the
 * scripted client is decorated like a real one, so it receives requests after redaction. The
 * factory makes its own privacy objects and exposes them, so a test can change them, read the log,
 * or hand the same objects to the facade.
 */
public final class ScriptedLlmClientFactory extends LlmClientFactory {

    public final List<ModelSettings> requested = new ArrayList<>();
    public final Redactor redactor;
    public final FolderPolicy folderPolicy;
    public final PrivacyLog privacyLog;
    private final LlmClient client;
    private final LlmException buildFailure;

    public ScriptedLlmClientFactory(LlmClient client) {
        this(client, null, new Redactor(SensitivePattern.builtIn()), new FolderPolicy(), tempLog());
    }

    /** A factory whose settings can't work, like a cloud backend without a key. */
    public ScriptedLlmClientFactory(LlmException buildFailure) {
        this(
                null,
                buildFailure,
                new Redactor(SensitivePattern.builtIn()),
                new FolderPolicy(),
                tempLog());
    }

    private ScriptedLlmClientFactory(
            LlmClient client,
            LlmException buildFailure,
            Redactor redactor,
            FolderPolicy folderPolicy,
            PrivacyLog privacyLog) {
        super(redactor, folderPolicy, privacyLog, Clock.systemUTC());
        this.client = client;
        this.buildFailure = buildFailure;
        this.redactor = redactor;
        this.folderPolicy = folderPolicy;
        this.privacyLog = privacyLog;
    }

    /** A privacy log in a temporary file of its own, never the user's. */
    private static PrivacyLog tempLog() {
        try {
            Path file = Files.createTempFile("findany-test-privacy-log", ".jsonl");
            file.toFile().deleteOnExit();
            return new PrivacyLog(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
