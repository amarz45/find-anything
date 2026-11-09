package io.github.amarz45.findanything.privacy;

import java.util.List;

/**
 * One kind of sensitive data that can be recognized by its shape (F14). There is one implementation
 * per category. A pattern only finds matches; {@link Redactor} replaces them.
 */
public interface SensitivePattern {

    /** The category's name, as the user turns it on or off: for example {@code card-number}. */
    String getCategory();

    /** Every piece of the text that matches, exactly as it is written there. */
    List<String> findAll(String text);

    /**
     * The patterns the app redacts with, in the order they are applied: private keys first, since a
     * key block is long and is taken whole, then card numbers, SINs and API keys.
     */
    static List<SensitivePattern> builtIn() {
        return List.of(
                new PrivateKeyPattern(),
                new CardNumberPattern(),
                new SinPattern(),
                new ApiKeyPattern());
    }
}
