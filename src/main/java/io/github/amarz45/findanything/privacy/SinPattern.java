package io.github.amarz45.findanything.privacy;

import java.util.List;

/**
 * Canadian Social Insurance Numbers: nine digits, written together or in groups separated by spaces
 * or hyphens (usually three groups of three), that pass the Luhn check.
 */
public final class SinPattern implements SensitivePattern {

    public static final String CATEGORY = "sin";

    private static final int DIGITS = 9;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public List<String> findAll(String text) {
        return Luhn.findNumbers(text, DIGITS, DIGITS);
    }
}
