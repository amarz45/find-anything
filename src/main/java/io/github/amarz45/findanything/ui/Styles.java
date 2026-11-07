package io.github.amarz45.findanything.ui;

import java.util.Objects;

/** The GUI's one stylesheet, {@code findany.css} next to this class, as a URL for JavaFX. */
final class Styles {

    static final String STYLESHEET =
            Objects.requireNonNull(Styles.class.getResource("findany.css"), "findany.css")
                    .toExternalForm();

    private Styles() {}
}
