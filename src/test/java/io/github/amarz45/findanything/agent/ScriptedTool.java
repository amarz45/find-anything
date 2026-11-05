package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A test double for {@link Tool}: returns the scripted result (or throws the scripted exception),
 * rejects a call whose "query" is "bad", and records the arguments it validated and executed.
 */
public final class ScriptedTool implements Tool {

    public final List<Map<String, Object>> validated = new ArrayList<>();
    public final List<Map<String, Object>> executed = new ArrayList<>();
    private final ToolSpec spec;
    private final ToolResult result;
    private final RuntimeException failure;

    public ScriptedTool(ToolSpec spec, ToolResult result) {
        this.spec = spec;
        this.result = result;
        this.failure = null;
    }

    public ScriptedTool(ToolSpec spec, RuntimeException failure) {
        this.spec = spec;
        this.result = null;
        this.failure = failure;
    }

    @Override
    public ToolSpec getSpec() {
        return spec;
    }

    @Override
    public List<String> validate(Map<String, Object> arguments) {
        validated.add(arguments);
        return "bad".equals(arguments.get("query"))
                ? List.of("The query \"bad\" isn't allowed.")
                : List.of();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        executed.add(arguments);
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
