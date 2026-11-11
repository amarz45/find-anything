package io.github.amarz45.findanything.privacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces sensitive data in text bound for a cloud model with placeholders (F14). Each {@link
 * SensitivePattern} is a category the user can turn off; all are on to begin with. Patterns are
 * applied in the order given.
 *
 * <p>Thread-safe: background request threads redact while the UI thread changes the categories.
 *
 * <p>Only data with a recognizable shape is found. Sensitive text without one, such as a medical
 * note or a password in a file with an ordinary name, passes through unchanged.
 */
public final class Redactor {

    private final List<SensitivePattern> sensitivePatterns;
    private final Set<String> disabledCategories = ConcurrentHashMap.newKeySet();

    public Redactor(List<SensitivePattern> sensitivePatterns) {
        this.sensitivePatterns = List.copyOf(Objects.requireNonNull(sensitivePatterns, "patterns"));
        List<String> categories = getCategories();
        if (categories.stream().distinct().count() != categories.size()) {
            throw new IllegalArgumentException("Two patterns share a category: " + categories);
        }
    }

    /** What stands in for a match of the category: for example {@code [redacted: card-number]}. */
    public static String placeholder(String category) {
        return "[redacted: " + category + "]";
    }

    /** Replaces every match of every enabled pattern with the category's placeholder. */
    public RedactionResult redact(String text) {
        String redacted = Objects.requireNonNull(text, "text");
        List<String> categories = new ArrayList<>();
        for (SensitivePattern pattern : sensitivePatterns) {
            String category = pattern.getCategory();
            if (disabledCategories.contains(category)) {
                continue;
            }
            List<String> matches = new ArrayList<>(pattern.findAll(redacted));
            if (matches.isEmpty()) {
                continue;
            }
            categories.add(category);
            // The longest first, so a match that contains a shorter one is replaced whole.
            matches.sort(Comparator.comparingInt(String::length).reversed());
            for (String match : matches) {
                redacted = redacted.replace(match, placeholder(category));
            }
        }
        return new RedactionResult(redacted, categories);
    }

    /**
     * Turns a category's redaction on or off, from the next call to {@link #redact} on.
     *
     * @throws IllegalArgumentException when there is no such category; the message lists them
     */
    public void setCategoryEnabled(String category, boolean enabled) {
        requireKnown(category);
        if (enabled) {
            disabledCategories.remove(category);
        } else {
            disabledCategories.add(category);
        }
    }

    /** Whether the category is redacted. */
    public boolean isCategoryEnabled(String category) {
        requireKnown(category);
        return !disabledCategories.contains(category);
    }

    /** Every category, in the order the patterns are applied. */
    public List<String> getCategories() {
        return sensitivePatterns.stream().map(SensitivePattern::getCategory).toList();
    }

    private void requireKnown(String category) {
        if (!getCategories().contains(Objects.requireNonNull(category, "category"))) {
            throw new IllegalArgumentException(
                    "There is no redaction category \""
                            + category
                            + "\". The categories are: "
                            + String.join(", ", getCategories())
                            + ".");
        }
    }
}
