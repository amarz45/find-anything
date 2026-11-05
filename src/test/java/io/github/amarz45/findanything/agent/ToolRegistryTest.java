package io.github.amarz45.findanything.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import io.github.amarz45.findanything.llm.ToolSpec.Parameter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    static final ToolSpec SEARCH =
            new ToolSpec(
                    "search",
                    "Searches the index.",
                    List.of(
                            Parameter.string("query", "What to look for.", true),
                            Parameter.integer("limit", "How many results.", false),
                            Parameter.oneOf("file_type", List.of("pdf", "docx"), "Type.", false),
                            Parameter.bool("names_only", "Match names only.", false)));
    static final ToolSpec READ = new ToolSpec("read_excerpt", "Reads a file.", List.of());
    static final ToolResult FOUND = ToolResult.success("lease.pdf", List.of(Path.of("lease.pdf")));

    ToolRegistry registry;
    ScriptedTool search;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        search = new ScriptedTool(SEARCH, FOUND);
        registry.register(search);
        registry.register(new ScriptedTool(READ, ToolResult.success("text", List.of())));
    }

    @Test
    void listsTheSpecsInRegistrationOrderAndRefusesADuplicateName() {
        assertThat(registry.getToolSpecs()).containsExactly(SEARCH, READ);
        assertThatThrownBy(() -> registry.register(new ScriptedTool(SEARCH, FOUND)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search");
    }

    @Test
    void executesAValidCallAndReturnsTheToolsResult() {
        Map<String, Object> arguments =
                Map.of("query", "lease", "limit", 5, "file_type", "pdf", "names_only", false);
        ToolResult result = registry.execute(new ToolCall("call-1", "search", arguments));
        assertThat(result).isEqualTo(FOUND);
        assertThat(search.validated).containsExactly(arguments);
        assertThat(search.executed).containsExactly(arguments);
    }

    @Test
    void acceptsAWholeNumberWrittenAsADecimalAndANullOptionalValue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "lease");
        arguments.put("limit", 5.0);
        arguments.put("file_type", null);
        assertThat(registry.execute(new ToolCall(null, "search", arguments)).succeeded()).isTrue();
    }

    @Test
    void reportsAnUnknownToolWithTheNamesThatExist() {
        ToolResult result = registry.execute(new ToolCall("c", "delete_file", Map.of()));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage())
                .contains("no tool named \"delete_file\"")
                .contains("search, read_excerpt");
    }

    @Test
    void rejectsArgumentsThatDoNotMatchTheSpecWithoutCallingTheTool() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", null);
        arguments.put("limit", "ten");
        arguments.put("file_type", "xlsx");
        arguments.put("names_only", "yes");
        arguments.put("folder", "taxes");
        ToolResult result = registry.execute(new ToolCall("c", "search", arguments));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage())
                .startsWith("Invalid call to search: ")
                .contains("Missing required parameter \"query\".")
                .contains("Parameter \"limit\" must be a whole number, but was \"ten\".")
                .contains("Parameter \"file_type\" must be one of pdf, docx, but was \"xlsx\".")
                .contains("Parameter \"names_only\" must be true or false, but was \"yes\".")
                .contains("Unknown parameter \"folder\"; the parameters are: query, limit,");
        assertThat(search.validated).isEmpty();
        assertThat(search.executed).isEmpty();

        assertThat(registry.execute(call(Map.of("query", 7))).errorMessage())
                .contains("Parameter \"query\" must be a string, but was 7.");
        assertThat(registry.execute(call(Map.of("query", "x", "limit", 2.5))).errorMessage())
                .contains("Parameter \"limit\" must be a whole number, but was 2.5.");
    }

    @Test
    void returnsTheToolsOwnValidationErrorsWithoutExecuting() {
        ToolResult result = registry.execute(call(Map.of("query", "bad")));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("Invalid call to search: The query \"bad\" isn't allowed.");
        assertThat(search.validated).hasSize(1);
        assertThat(search.executed).isEmpty();
    }

    @Test
    void reportsAToolThatThrowsAsAFailure() {
        ToolRegistry failing = new ToolRegistry();
        failing.register(new ScriptedTool(SEARCH, new IllegalStateException("index closed")));
        ToolResult result = failing.execute(call(Map.of("query", "lease")));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage())
                .contains("The tool search failed")
                .contains("index closed");
    }

    @Test
    void logsEveryCallInOrderIncludingTheRejectedOnes() {
        ToolCall valid = call(Map.of("query", "lease"));
        ToolCall invalid = call(Map.of());
        ToolCall unknown = new ToolCall("c", "delete_file", Map.of());
        assertThat(registry.getCallLog()).isEmpty();
        registry.execute(valid);
        registry.execute(invalid);
        registry.execute(unknown);
        List<ToolCall> callLog = registry.getCallLog();
        assertThat(callLog).containsExactly(valid, invalid, unknown);
        assertThatThrownBy(() -> callLog.add(valid))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ToolCall call(Map<String, Object> arguments) {
        return new ToolCall("c", "search", arguments);
    }
}
