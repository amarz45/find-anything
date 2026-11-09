package io.github.amarz45.findanything.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API keys and access tokens that start with a well-known prefix: {@code sk-} (OpenAI, Anthropic,
 * OpenRouter), GitHub's {@code ghp_} family and {@code github_pat_}, AWS's {@code AKIA}, Slack's
 * {@code xoxb-} family, and Google's {@code AIza}. A key with no recognizable prefix can't be told
 * from any other random string, so it isn't found.
 */
public final class ApiKeyPattern implements SensitivePattern {

    public static final String CATEGORY = "api-key";

    private static final Pattern KEY =
            Pattern.compile(
                    "(?<![A-Za-z0-9])(?:"
                            + "sk-[A-Za-z0-9_-]{16,}"
                            + "|gh[pousr]_[A-Za-z0-9]{36,}"
                            + "|github_pat_[A-Za-z0-9_]{22,}"
                            + "|AKIA[0-9A-Z]{16}"
                            + "|xox[abprs]-[A-Za-z0-9-]{10,}"
                            + "|AIza[0-9A-Za-z_-]{35}"
                            + ")");

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public List<String> findAll(String text) {
        List<String> found = new ArrayList<>();
        Matcher key = KEY.matcher(text);
        while (key.find()) {
            found.add(key.group());
        }
        return found;
    }
}
