package io.github.amarz45.findanything.privacy;

import java.util.List;
import java.util.Objects;

/**
 * What {@link Redactor#redact} produced.
 *
 * @param text the text with every match replaced by its placeholder
 * @param categories the categories that had at least one match, in the order they were applied;
 *     empty when nothing was redacted
 */
public record RedactionResult(String text, List<String> categories) {

    public RedactionResult {
        Objects.requireNonNull(text, "text");
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
    }
}
