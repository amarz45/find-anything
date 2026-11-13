package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.ToolCall;
import io.github.amarz45.findanything.llm.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The invoker of the Command pattern: looks a tool up by the name the model gave, validates the
 * arguments, executes the tool, and records every call in its call log. An invalid call is never
 * executed; it comes back as {@link ToolResult#failure} with the errors, so the model can correct
 * itself (F07).
 *
 * <p>Tools are registered at startup. {@link #execute} may be called from any background thread;
 * the tool itself runs outside the registry's lock.
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<ToolCall> callLog = new ArrayList<>();

    /**
     * Adds a tool under its spec's name.
     *
     * @throws IllegalArgumentException when a tool with that name is already registered
     */
    public synchronized void register(Tool tool) {
        String name = Objects.requireNonNull(tool, "tool").getSpec().name();
        if (tools.putIfAbsent(name, tool) != null) {
            throw new IllegalArgumentException("A tool named " + name + " is already registered.");
        }
    }

    /** The description of every registered tool, in registration order, to send to the model. */
    public synchronized List<ToolSpec> getToolSpecs() {
        return tools.values().stream().map(Tool::getSpec).toList();
    }

    /**
     * Runs one tool call from the model. The call is logged first, whatever happens to it. Then: an
     * unknown tool name, arguments that don't match the tool's spec, and anything {@link
     * Tool#validate} objects to each return a failure naming the problems, without executing. A
     * tool that throws is reported as a failure too, so one broken call can't end the request.
     *
     * @param shownPassages the passages the call's request has already been given, passed on to the
     *     tool
     */
    public ToolResult execute(ToolCall toolCall, ShownPassages shownPassages) {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(shownPassages, "shownPassages");
        Tool tool;
        synchronized (this) {
            callLog.add(toolCall);
            tool = tools.get(toolCall.name());
            if (tool == null) {
                return ToolResult.failure(
                        "There is no tool named \""
                                + toolCall.name()
                                + "\". The tools are: "
                                + String.join(", ", tools.keySet())
                                + ".");
            }
        }
        Map<String, Object> arguments = toolCall.arguments();
        List<String> errors = checkAgainstSpec(tool.getSpec(), arguments);
        if (errors.isEmpty()) {
            errors = tool.validate(arguments);
        }
        if (!errors.isEmpty()) {
            return ToolResult.failure(
                    "Invalid call to " + toolCall.name() + ": " + String.join(" ", errors));
        }
        try {
            return tool.execute(arguments, shownPassages);
        } catch (RuntimeException e) {
            log.error("Tool {} failed on {}", toolCall.name(), arguments, e);
            return ToolResult.failure("The tool " + toolCall.name() + " failed: " + e);
        }
    }

    /**
     * Every call the model has made, in order, including the rejected ones. Stage 3's behaviour
     * tests read these traces.
     */
    public synchronized List<ToolCall> getCallLog() {
        return List.copyOf(callLog);
    }

    /** The checks every tool shares: required parameters, unknown parameters, and value types. */
    private static List<String> checkAgainstSpec(ToolSpec spec, Map<String, Object> arguments) {
        List<String> errors = new ArrayList<>();
        List<String> names = spec.parameters().stream().map(ToolSpec.Parameter::name).toList();
        for (String name : arguments.keySet()) {
            if (!names.contains(name)) {
                errors.add(
                        "Unknown parameter \""
                                + name
                                + "\"; the parameters are: "
                                + String.join(", ", names)
                                + ".");
            }
        }
        for (ToolSpec.Parameter parameter : spec.parameters()) {
            Object value = arguments.get(parameter.name());
            if (value == null) {
                if (parameter.required()) {
                    errors.add("Missing required parameter \"" + parameter.name() + "\".");
                }
                continue;
            }
            String problem =
                    switch (parameter.type()) {
                        case STRING -> checkString(parameter, value);
                        case INTEGER -> isWholeNumber(value) ? null : "must be a whole number";
                        case BOOLEAN -> value instanceof Boolean ? null : "must be true or false";
                    };
            if (problem != null) {
                errors.add(
                        "Parameter \""
                                + parameter.name()
                                + "\" "
                                + problem
                                + ", but was "
                                + describe(value)
                                + ".");
            }
        }
        return errors;
    }

    private static String checkString(ToolSpec.Parameter parameter, Object value) {
        if (!(value instanceof String text)) {
            return "must be a string";
        }
        List<String> allowed = parameter.allowedValues();
        if (!allowed.isEmpty() && !allowed.contains(text)) {
            return "must be one of " + String.join(", ", allowed);
        }
        return null;
    }

    /** A JSON number with no fraction that fits an int; models also write 5 as 5.0. */
    private static boolean isWholeNumber(Object value) {
        return value instanceof Number number
                && number.doubleValue() == Math.rint(number.doubleValue())
                && Math.abs(number.doubleValue()) <= Integer.MAX_VALUE;
    }

    private static String describe(Object value) {
        return value instanceof String text ? "\"" + text + "\"" : String.valueOf(value);
    }
}
