package io.github.amarz45.findanything.config;

/** An exclusion pattern the user entered can't be parsed. The message says why. */
public class InvalidPatternException extends Exception {

    public InvalidPatternException(String message) {
        super(message);
    }
}
