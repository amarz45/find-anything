package io.github.amarz45.findanything.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private keys in PEM form: everything from a {@code -----BEGIN ... PRIVATE KEY-----} line to its
 * {@code -----END ... PRIVATE KEY-----} line. A block whose end is missing, for example because an
 * excerpt stopped inside it, is taken to the end of the text.
 */
public final class PrivateKeyPattern implements SensitivePattern {

    public static final String CATEGORY = "private-key";

    private static final Pattern BLOCK =
            Pattern.compile(
                    "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"
                            + "[\\s\\S]*?"
                            + "(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)");

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public List<String> findAll(String text) {
        List<String> found = new ArrayList<>();
        Matcher block = BLOCK.matcher(text);
        while (block.find()) {
            found.add(block.group());
        }
        return found;
    }
}
