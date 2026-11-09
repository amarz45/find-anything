package io.github.amarz45.findanything.privacy;

import java.util.List;

/**
 * Payment card numbers: 13 to 19 digits, written together or in groups separated by spaces or
 * hyphens, that pass the Luhn check.
 */
public final class CardNumberPattern implements SensitivePattern {

    public static final String CATEGORY = "card-number";

    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public List<String> findAll(String text) {
        return Luhn.findNumbers(text, MIN_DIGITS, MAX_DIGITS);
    }
}
