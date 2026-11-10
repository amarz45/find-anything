package io.github.amarz45.findanything.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The four patterns, on the test corpus's fake values and on near misses. */
class SensitivePatternTest {

    // The values in testdata/corpus/private/bank-details.txt: fake, but valid in form.
    static final String CARD = "4111 1111 1111 1111";
    static final String SIN = "046 454 286";
    static final String API_KEY = "sk-test-FAKE-1234567890abcdef";

    final SensitivePattern card = new CardNumberPattern();
    final SensitivePattern sin = new SinPattern();
    final SensitivePattern privateKey = new PrivateKeyPattern();
    final SensitivePattern apiKey = new ApiKeyPattern();

    @Test
    void luhnAcceptsValidNumbersAndRejectsOneDigitOff() {
        assertThat(Luhn.isValid("4111111111111111")).isTrue();
        assertThat(Luhn.isValid("4111 1111 1111 1111")).isTrue();
        assertThat(Luhn.isValid("046-454-286")).isTrue();
        assertThat(Luhn.isValid("4111111111111112")).isFalse();
        assertThat(Luhn.isValid("046454287")).isFalse();
        assertThat(Luhn.isValid("no digits")).isFalse();
    }

    @Test
    void findsCardNumbersHoweverTheyAreGrouped() {
        assertThat(card.getCategory()).isEqualTo("card-number");
        assertThat(card.findAll("Card number: " + CARD)).containsExactly(CARD);
        assertThat(card.findAll("pay with 4111-1111-1111-1111 today"))
                .containsExactly("4111-1111-1111-1111");
        assertThat(card.findAll("4111111111111111")).containsExactly("4111111111111111");
        // American Express: 15 digits in groups of 4, 6 and 5.
        assertThat(card.findAll("Amex 3782 822463 10005.")).containsExactly("3782 822463 10005");
        assertThat(card.findAll("two: 4111111111111111 and 5500 0000 0000 0004"))
                .containsExactly("4111111111111111", "5500 0000 0000 0004");
    }

    @Test
    void leavesNumbersThatAreNotCardNumbers() {
        assertThat(card.findAll("4111 1111 1111 1112")).isEmpty(); // fails the checksum
        assertThat(card.findAll("phone 416 555 0199, invoice 2026-11-04")).isEmpty();
        assertThat(card.findAll("411111111111")).isEmpty(); // twelve digits
        assertThat(card.findAll("41111111111111111111")).isEmpty(); // twenty digits in one group
        assertThat(card.findAll("no numbers here")).isEmpty();
    }

    @Test
    void findsACardNumberFollowedByAnotherNumberOnTheSameLine() {
        String line = CARD + " " + SIN;
        assertThat(card.findAll(line)).containsExactly(CARD);
        assertThat(sin.findAll(line)).containsExactly(SIN);
    }

    @Test
    void findsSinsAndLeavesOtherNineDigitNumbers() {
        assertThat(sin.getCategory()).isEqualTo("sin");
        assertThat(sin.findAll("SIN: " + SIN)).containsExactly(SIN);
        assertThat(sin.findAll("046-454-286")).containsExactly("046-454-286");
        assertThat(sin.findAll("046454286")).containsExactly("046454286");
        assertThat(sin.findAll("046 454 287")).isEmpty(); // fails the checksum
        assertThat(sin.findAll("call 416 555 0199")).isEmpty(); // ten digits
        assertThat(sin.findAll(CARD)).isEmpty();
    }

    @Test
    void findsAPrivateKeyBlockWholeEvenWhenItsEndIsCutOff() {
        String block =
                "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\nAAAAB3Nza\n"
                        + "-----END OPENSSH PRIVATE KEY-----";
        assertThat(privateKey.getCategory()).isEqualTo("private-key");
        assertThat(privateKey.findAll("key:\n" + block + "\nafter")).containsExactly(block);
        assertThat(privateKey.findAll("-----BEGIN PRIVATE KEY-----\nMIIEvQ"))
                .containsExactly("-----BEGIN PRIVATE KEY-----\nMIIEvQ");
        assertThat(privateKey.findAll("-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----"))
                .isEmpty();
    }

    @Test
    void findsApiKeysByTheirPrefix() {
        assertThat(apiKey.getCategory()).isEqualTo("api-key");
        assertThat(apiKey.findAll("API key: " + API_KEY)).containsExactly(API_KEY);
        assertThat(apiKey.findAll("AWS AKIAIOSFODNN7EXAMPLE."))
                .containsExactly("AKIAIOSFODNN7EXAMPLE");
        assertThat(apiKey.findAll("token ghp_0123456789abcdefghijklmnopqrstuvwxyz"))
                .containsExactly("ghp_0123456789abcdefghijklmnopqrstuvwxyz");
        // Ordinary words that merely contain "sk-" are left alone.
        assertThat(apiKey.findAll("the task-force-and-risk-management-committee")).isEmpty();
        assertThat(apiKey.findAll("sk-short")).isEmpty();
    }

    @Test
    void digitsThatArePartOfANameAreNotANumber() {
        // The same valid numbers, glued to a letter, an underscore or a hyphen.
        assertThat(card.findAll("/tmp/junit4111111111111111/docs/notes.txt")).isEmpty();
        assertThat(card.findAll("IMG_4111111111111111.jpg")).isEmpty();
        assertThat(card.findAll("report-4111111111111111.pdf")).isEmpty();
        assertThat(card.findAll("4111111111111111-copy")).isEmpty();
        assertThat(sin.findAll("invoice-046454286 and ref046454286")).isEmpty();
        // Punctuation around a number doesn't hide it.
        assertThat(card.findAll("(4111111111111111), then")).containsExactly("4111111111111111");
        assertThat(sin.findAll("SIN:046-454-286.")).containsExactly("046-454-286");
    }

    @Test
    void theBuiltInPatternsCoverTheFourCategoriesInOrder() {
        assertThat(SensitivePattern.builtIn())
                .extracting(SensitivePattern::getCategory)
                .isEqualTo(List.of("private-key", "card-number", "sin", "api-key"));
    }
}
