package io.github.amarz45.findanything.llm;

import java.util.List;
import java.util.Objects;

/**
 * Describes a tool to the model: its name, what it does, and its parameters (the Command pattern's
 * {@code Tool.getSpec()}). It is written in the system's own terms; {@link LangChain4jClient} turns
 * it into the provider's JSON schema.
 *
 * @param name the tool's name, as the model will call it
 * @param description what the tool does and when to use it
 * @param parameters the parameters, in the order they are described to the model
 */
public record ToolSpec(String name, String description, List<Parameter> parameters) {

    /** A parameter's JSON type. */
    public enum Type {
        STRING,
        INTEGER,
        BOOLEAN
    }

    /**
     * One parameter of a tool.
     *
     * @param name the parameter's name
     * @param type its JSON type
     * @param description what it means, for the model
     * @param required whether the model must give it
     * @param allowedValues the only values accepted, for a string parameter that is a choice; empty
     *     when any value is accepted
     */
    public record Parameter(
            String name,
            Type type,
            String description,
            boolean required,
            List<String> allowedValues) {

        public Parameter {
            requireName(name);
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
            allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
            if (!allowedValues.isEmpty() && type != Type.STRING) {
                throw new IllegalArgumentException(
                        "Only a string parameter can list its allowed values: " + name);
            }
        }

        public static Parameter string(String name, String description, boolean required) {
            return new Parameter(name, Type.STRING, description, required, List.of());
        }

        public static Parameter integer(String name, String description, boolean required) {
            return new Parameter(name, Type.INTEGER, description, required, List.of());
        }

        public static Parameter bool(String name, String description, boolean required) {
            return new Parameter(name, Type.BOOLEAN, description, required, List.of());
        }

        /** A string parameter whose value must be one of {@code allowedValues}. */
        public static Parameter oneOf(
                String name, List<String> allowedValues, String description, boolean required) {
            if (allowedValues.isEmpty()) {
                throw new IllegalArgumentException("oneOf needs at least one value: " + name);
            }
            return new Parameter(name, Type.STRING, description, required, allowedValues);
        }
    }

    public ToolSpec {
        requireName(name);
        Objects.requireNonNull(description, "description");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        long distinct = parameters.stream().map(Parameter::name).distinct().count();
        if (distinct != parameters.size()) {
            throw new IllegalArgumentException("Tool " + name + " repeats a parameter name.");
        }
    }

    private static void requireName(String name) {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("A name is required.");
        }
    }
}
