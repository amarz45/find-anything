package io.github.amarz45.findanything.config;

/** A folder can't be added because it is inside, or contains, an existing search folder. */
public class AlreadyCoveredException extends Exception {

    public AlreadyCoveredException(String message) {
        super(message);
    }
}
