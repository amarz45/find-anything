package io.github.amarz45.findanything.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool call requested by the model: the tool's name and its arguments (SD03). The arguments are
 * the model's JSON object as plain Java values (strings, numbers, booleans, lists, maps, or null),
 * ready for {@code Tool.validate()}.
 *
 * @param id the provider's id for this call, which the tool's result must carry back; null for a
 *     provider that gives none (Ollama)
 * @param name the tool's name
 * @param arguments the arguments by parameter name, in the model's order; a value may be null
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("A tool call needs a tool name.");
        }
        // Not Map.copyOf: a JSON null is a legal argument value, and Map.copyOf rejects nulls.
        arguments =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(arguments, "arguments")));
    }
}
