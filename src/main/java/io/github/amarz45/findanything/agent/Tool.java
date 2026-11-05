package io.github.amarz45.findanything.agent;

import io.github.amarz45.findanything.llm.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * One operation the model can ask for, as a command object (the Command pattern). The model names a
 * tool and gives JSON arguments; {@link ToolRegistry} finds the tool, validates the call and
 * executes it, so every tool gets the same checks, error handling and logging.
 *
 * <p>A tool is deterministic and only reads the index, so it is safe to call from any background
 * thread.
 */
public interface Tool {

    /** Describes the tool to the model: its name, its purpose and its parameters. */
    ToolSpec getSpec();

    /**
     * Checks what the spec can't express, such as a malformed date or an unknown file type. {@link
     * ToolRegistry} calls it only after the arguments match the spec: every required parameter is
     * present, nothing is unknown, and each value has its parameter's type.
     *
     * @param arguments the call's arguments by parameter name; an optional parameter may be absent
     *     or null
     * @return the problems, worded for the model so it can correct the call; empty when the call is
     *     valid
     */
    List<String> validate(Map<String, Object> arguments);

    /**
     * Runs the tool. Called only with arguments that passed {@link #validate}. A failure the tool
     * expects, such as a file that isn't indexed, is returned as {@link ToolResult#failure}, not
     * thrown.
     */
    ToolResult execute(Map<String, Object> arguments);
}
