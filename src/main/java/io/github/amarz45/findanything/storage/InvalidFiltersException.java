package io.github.amarz45.findanything.storage;

import java.util.List;

/** The search filters don't make sense (F04); {@link #getErrors()} lists each problem. */
public class InvalidFiltersException extends Exception {

    private final List<String> errors;

    public InvalidFiltersException(List<String> errors) {
        super(String.join(" ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
