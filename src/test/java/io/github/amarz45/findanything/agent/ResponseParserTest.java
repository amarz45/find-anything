package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.llm.LlmResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseParserTest {

    static final Path LEASE = Path.of("data", "lease 2025.pdf").toAbsolutePath();
    static final Path NOTES = Path.of("data", "notes.md").toAbsolutePath();

    final ResponseParser parser = new ResponseParser();

    Answer parse(String text) throws AnswerFormatException {
        return parser.parseAnswer(new LlmResponse(text, List.of()));
    }

    @Test
    void parsesTheAnswerAndItsCitations() throws Exception {
        Answer answer =
                parse(
                        """
                        Your lease ends on August 31, 2026 [1].
                        Renew it in the spring [2].

                        CITATIONS:
                        [1] file: %s
                        page: 2
                        quote: The lease ends on August 31, 2026.
                        [2] file: %s
                        quote: Renew the lease in the spring.
                        """
                                .formatted(LEASE, NOTES));
        assertThat(answer.text())
                .isEqualTo("Your lease ends on August 31, 2026 [1].\nRenew it in the spring [2].");
        assertThat(answer.citations())
                .containsExactly(
                        new Citation(LEASE, 2, "The lease ends on August 31, 2026."),
                        new Citation(NOTES, 0, "Renew the lease in the spring."));
        assertThat(answer.citations())
                .allSatisfy(c -> assertThat(c.status()).isEqualTo(VerificationStatus.UNVERIFIED));
        assertThat(answer.sourceFiles()).isEmpty();
        assertThat(answer.notices()).isEmpty();
        assertThat(answer.isVerified()).isFalse();
    }

    @Test
    void aReplyWithoutMarkersOrEntriesIsAnAnswerWithoutCitations() throws Exception {
        Answer question = parse("Which lease do you mean: the 2024 one or the 2025 one?");
        assertThat(question.text())
                .isEqualTo("Which lease do you mean: the 2024 one or the 2025 one?");
        assertThat(question.citations()).isEmpty();

        Answer nothing = parse("I found nothing about that.\n\nCITATIONS:\nnone\n");
        assertThat(nothing.text()).isEqualTo("I found nothing about that.");
        assertThat(nothing.citations()).isEmpty();
    }

    @Test
    void forgivesMarkdownDecorationFencesAndQuotationMarks() throws Exception {
        Answer answer =
                parse(
                        """
                        ```text
                        **Answer:** The rent is $1,850 [1].

                        ## Sources
                        - [1] **File:** `%s`
                          - **Slide:** slide 3
                          - **Quote:** "Rent is $1,850
                            per month."
                        ```
                        """
                                .formatted(LEASE));
        assertThat(answer.text()).isEqualTo("The rent is $1,850 [1].");
        assertThat(answer.citations())
                .containsExactly(new Citation(LEASE, 3, "Rent is $1,850 per month."));
    }

    @Test
    void renumbersMarkersToTheOrderOfTheCitations() throws Exception {
        Answer answer =
                parse(
                        """
                        Two facts [7][3], and again [3, 7].

                        CITATIONS:
                        [3] file: %s
                        page: none
                        quote: first
                        [7] file: %s
                        page: p. 4
                        quote: second
                        """
                                .formatted(NOTES, LEASE));
        assertThat(answer.text()).isEqualTo("Two facts [2][1], and again [1, 2].");
        assertThat(answer.citations())
                .containsExactly(new Citation(NOTES, 0, "first"), new Citation(LEASE, 4, "second"));
    }

    @Test
    void keepsACitationWithoutAQuoteForTheVerifierToReject() throws Exception {
        Answer answer = parse("See the file [1].\nCITATIONS:\n[1] file: " + LEASE);
        assertThat(answer.citations()).containsExactly(new Citation(LEASE, 0, ""));
    }

    @Test
    void rejectsAMarkerWithoutACitation() {
        assertThatThrownBy(() -> parse("Your lease ends on August 31, 2026 [1]."))
                .isInstanceOf(AnswerFormatException.class)
                .hasMessage(
                        "The answer uses [1] but there is no citation [1] under a CITATIONS:"
                                + " line.");
        assertThatThrownBy(
                        () ->
                                parse(
                                        "One [1], two [2], two again [2].\nCITATIONS:\n[1] file: "
                                                + LEASE
                                                + "\nquote: x"))
                .isInstanceOf(AnswerFormatException.class)
                .hasMessage(
                        "The answer uses [2] but there is no citation [2] under a CITATIONS:"
                                + " line.");
    }

    @Test
    void rejectsEntriesWithoutAUsableFileOrPageAndListsEveryProblem() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        Facts [1] [2] [3] [4].

                                        CITATIONS:
                                        [1] quote: no file here
                                        [2] file: lease.pdf
                                        quote: a relative path
                                        [3] file: %s
                                        page: the second one
                                        quote: a page in words
                                        [4] file: %s
                                        quote: fine
                                        [4] file: %s
                                        quote: listed twice
                                        """
                                                .formatted(LEASE, LEASE, LEASE)))
                .isInstanceOf(AnswerFormatException.class)
                .hasMessageContaining("Citation [1] has no \"file:\" line.")
                .hasMessageContaining(
                        "Citation [2] must give the file's full path as the tools gave it, but"
                                + " has lease.pdf.")
                .hasMessageContaining(
                        "Citation [3] has a page that isn't a number: the second one.")
                .hasMessageContaining("Citation [4] is listed twice.");
    }

    @Test
    void rejectsAnEmptyReplyAndCitationsWithoutAnAnswer() {
        assertThatThrownBy(() -> parse("  \n"))
                .isInstanceOf(AnswerFormatException.class)
                .hasMessage("The reply is empty.");
        assertThatThrownBy(() -> parse("CITATIONS:\n[1] file: " + LEASE + "\nquote: x"))
                .isInstanceOf(AnswerFormatException.class)
                .hasMessage("There is no answer text before the CITATIONS: line.");
    }
}
