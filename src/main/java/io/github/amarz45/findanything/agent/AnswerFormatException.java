package io.github.amarz45.findanything.agent;

/**
 * The model's final reply isn't in the answer format, so its citations can't be read (F09). The
 * message lists what is wrong, worded for the model: the agent asks it once to reformat.
 */
public class AnswerFormatException extends Exception {

    public AnswerFormatException(String message) {
        super(message);
    }
}
