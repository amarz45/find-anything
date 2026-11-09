package io.github.amarz45.findanything.llm;

import io.github.amarz45.findanything.privacy.FolderPolicy;
import io.github.amarz45.findanything.privacy.RedactionResult;
import io.github.amarz45.findanything.privacy.Redactor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps protected content out of a request to a cloud model (F14, SD04). A message whose text came
 * from a file in a "local model only" folder has its whole text replaced with {@link #WITHHELD}.
 * Every other message has its sensitive data replaced with placeholders by the {@link Redactor}.
 * The categories that were redacted travel on with the request, for the privacy log.
 *
 * <p>A message is withheld whole, even when only one of its source files is local-only: a search
 * result lists several files in one text, and this client can't tell their passages apart.
 */
public final class RedactingLlmClient extends LlmClientDecorator {

    /** What a message from a local-only folder is replaced with. */
    public static final String WITHHELD = "[withheld: local-only source]";

    private final Redactor redactor;
    private final FolderPolicy folderPolicy;

    public RedactingLlmClient(
            LlmClient wrappedClient, Redactor redactor, FolderPolicy folderPolicy) {
        super(wrappedClient);
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.folderPolicy = Objects.requireNonNull(folderPolicy, "folderPolicy");
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<Message> messages = new ArrayList<>();
        Set<String> categories = new LinkedHashSet<>(request.redactedCategories());
        for (Message message : request.messages()) {
            if (message.sourceFiles().stream().anyMatch(folderPolicy::isLocalOnly)) {
                messages.add(withContent(message, WITHHELD));
                continue;
            }
            RedactionResult result = redactor.redact(message.content());
            categories.addAll(result.categories());
            messages.add(
                    result.categories().isEmpty() ? message : withContent(message, result.text()));
        }
        return wrappedClient.complete(
                new LlmRequest(messages, request.toolSpecs(), List.copyOf(categories)));
    }

    private static Message withContent(Message message, String content) {
        return new Message(
                message.role(),
                content,
                message.sourceFiles(),
                message.toolCalls(),
                message.toolCallId(),
                message.toolName());
    }
}
