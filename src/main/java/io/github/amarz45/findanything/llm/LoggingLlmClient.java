package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.config.ModelSettings;
import io.github.amarz45.findanything.privacy.PrivacyLog;
import io.github.amarz45.findanything.privacy.PrivacyLogEntry;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Records each request to a cloud model in the {@link PrivacyLog} before it is sent (F13, SD04). It
 * sits inside {@link RedactingLlmClient}, so the log shows exactly what goes out: the text after
 * local-only content was withheld and sensitive data redacted. A request that then fails is still
 * in the log, since it was sent or about to be.
 *
 * <p>A request that can't be recorded is not sent.
 */
public final class LoggingLlmClient extends LlmClientDecorator {

    private final PrivacyLog privacyLog;
    private final ModelSettings modelSettings;
    private final Clock clock;

    /**
     * @param modelSettings the cloud model the wrapped client talks to, named in each entry
     * @param clock gives each entry's time
     */
    public LoggingLlmClient(
            LlmClient wrappedClient,
            PrivacyLog privacyLog,
            ModelSettings modelSettings,
            Clock clock) {
        super(wrappedClient);
        this.privacyLog = Objects.requireNonNull(privacyLog, "privacyLog");
        this.modelSettings = Objects.requireNonNull(modelSettings, "modelSettings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<PrivacyLogEntry.Excerpt> excerpts = new ArrayList<>();
        for (Message message : request.messages()) {
            excerpts.add(
                    new PrivacyLogEntry.Excerpt(
                            message.role().name().toLowerCase(Locale.ROOT),
                            message.sourceFiles(),
                            textOf(message)));
        }
        PrivacyLogEntry entry =
                new PrivacyLogEntry(
                        clock.instant(),
                        modelSettings.modelName(),
                        excerpts,
                        request.redactedCategories());
        try {
            privacyLog.append(entry);
        } catch (IOException e) {
            throw new LlmException(
                    "The request was not sent, because it could not be recorded in the privacy log "
                            + privacyLog.getLogFile()
                            + ": "
                            + e.getMessage(),
                    e);
        }
        return wrappedClient.complete(request);
    }

    /** The message's text, followed by the tool calls it made, since those are sent too. */
    private static String textOf(Message message) {
        StringBuilder text = new StringBuilder(message.content());
        for (ToolCall toolCall : message.toolCalls()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("[tool call: ")
                    .append(toolCall.name())
                    .append(' ')
                    .append(toolCall.arguments())
                    .append(']');
        }
        return text.toString();
    }
}
