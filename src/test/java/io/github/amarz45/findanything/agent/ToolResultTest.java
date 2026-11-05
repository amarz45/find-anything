package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void successCarriesTheContentAndItsSourceFiles() {
        List<Path> files = new ArrayList<>(List.of(Path.of("lease.pdf")));
        ToolResult result = ToolResult.success("ends August 31", files);
        files.clear();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.content()).isEqualTo("ends August 31");
        assertThat(result.errorMessage()).isEmpty();
        assertThat(result.sourceFiles()).containsExactly(Path.of("lease.pdf"));
    }

    @Test
    void failureCarriesOnlyTheErrorMessage() {
        ToolResult result = ToolResult.failure("No such file.");
        assertThat(result.succeeded()).isFalse();
        assertThat(result.content()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("No such file.");
        assertThat(result.sourceFiles()).isEmpty();
    }

    @Test
    void rejectsAFailureWithoutAMessageAndASuccessWithOne() {
        assertThatThrownBy(() -> ToolResult.failure(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolResult(true, "text", "oops", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
