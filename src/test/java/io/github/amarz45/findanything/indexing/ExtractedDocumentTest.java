package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractedDocumentTest {

    @Test
    void copiesThePageList() {
        List<String> pages = new ArrayList<>(List.of("one", "two"));
        ExtractedDocument document = new ExtractedDocument("application/pdf", pages, true, false);
        pages.add("three");
        assertThat(document.pages()).containsExactly("one", "two");
    }

    @Test
    void reportsWhenNoPageHasText() {
        assertThat(
                        new ExtractedDocument("application/pdf", List.of("", " \n"), true, false)
                                .hasNoText())
                .isTrue();
        assertThat(new ExtractedDocument("text/plain", List.of("hello"), false, false).hasNoText())
                .isFalse();
    }

    @Test
    void needsAtLeastOnePageAndExactlyOneWhenUnnumbered() {
        assertThatThrownBy(() -> new ExtractedDocument("text/plain", List.of(), false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new ExtractedDocument("text/plain", List.of("a", "b"), false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
