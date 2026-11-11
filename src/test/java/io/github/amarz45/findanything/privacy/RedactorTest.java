package io.github.amarz45.findanything.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactorTest {

    /** The corpus file that F14's tests are meant to use: a fake card number, SIN and API key. */
    static String bankDetails() throws IOException {
        return Files.readString(Path.of("testdata", "corpus", "private", "bank-details.txt"));
    }

    @Test
    void replacesEveryMatchWithItsCategorysPlaceholder() throws IOException {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        RedactionResult result = redactor.redact(bankDetails());

        assertThat(result.text())
                .contains("Card number: [redacted: card-number]")
                .contains("SIN: [redacted: sin]")
                .contains("API key: [redacted: api-key]")
                .contains("FAKE TEST DATA, NOT REAL")
                .doesNotContain("4111")
                .doesNotContain("046 454 286")
                .doesNotContain("sk-test");
        assertThat(result.categories()).containsExactly("card-number", "sin", "api-key");
    }

    @Test
    void leavesTextWithoutMatchesAsItIs() {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        String text = "The lease ends on August 31, 2026. Rent is $1,850.";
        RedactionResult result = redactor.redact(text);
        assertThat(result.text()).isEqualTo(text);
        assertThat(result.categories()).isEmpty();
    }

    @Test
    void replacesEveryOccurrenceAndAWholeKeyBlock() {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        String text =
                "first 4111 1111 1111 1111, again 4111 1111 1111 1111\n"
                        + "-----BEGIN RSA PRIVATE KEY-----\nMIIEow 4111111111111111\n"
                        + "-----END RSA PRIVATE KEY-----\ndone";
        RedactionResult result = redactor.redact(text);
        assertThat(result.text())
                .isEqualTo(
                        "first [redacted: card-number], again [redacted: card-number]\n"
                                + "[redacted: private-key]\ndone");
        assertThat(result.categories()).containsExactly("private-key", "card-number");
    }

    @Test
    void aDisabledCategoryIsLeftAloneUntilItIsEnabledAgain() throws IOException {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        assertThat(redactor.getCategories())
                .containsExactly("private-key", "card-number", "sin", "api-key");
        assertThat(redactor.isCategoryEnabled("sin")).isTrue();

        redactor.setCategoryEnabled("sin", false);
        assertThat(redactor.isCategoryEnabled("sin")).isFalse();
        RedactionResult result = redactor.redact(bankDetails());
        assertThat(result.text()).contains("SIN: 046 454 286").doesNotContain("4111");
        assertThat(result.categories()).containsExactly("card-number", "api-key");

        redactor.setCategoryEnabled("sin", true);
        assertThat(redactor.redact(bankDetails()).text()).contains("SIN: [redacted: sin]");
    }

    @Test
    void refusesACategoryItDoesNotHaveAndNamesTheOnesItHas() {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        assertThatThrownBy(() -> redactor.setCategoryEnabled("email", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "There is no redaction category \"email\". The categories are:"
                                + " private-key, card-number, sin, api-key.");
        assertThatThrownBy(() -> redactor.isCategoryEnabled("email"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Redactor(List.of(new SinPattern(), new SinPattern())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placeholdersAreNotThemselvesRedacted() {
        Redactor redactor = new Redactor(SensitivePattern.builtIn());
        String once =
                redactor.redact("card 4111 1111 1111 1111 key sk-test-FAKE-1234567890abcdef")
                        .text();
        assertThat(redactor.redact(once).text()).isEqualTo(once);
        assertThat(redactor.redact(once).categories()).isEmpty();
    }
}
