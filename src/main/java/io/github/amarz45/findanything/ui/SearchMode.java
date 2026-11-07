package io.github.amarz45.findanything.ui;

/** The two ways the main window's input field is used: instant search (F06) or Ask mode (F07). */
public enum SearchMode {
    /** Each change of the text runs an instant search. */
    SEARCH,
    /** Enter sends the text to the agent as a request in the current conversation. */
    ASK
}
