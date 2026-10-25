package io.github.amarz45.findanything.config;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;

/**
 * A glob pattern that keeps files out of the index (F01, F14). Rules apply recursively to every
 * subfolder of the search folder they belong to.
 *
 * <p>A pattern without a slash, such as {@code *.tmp} or {@code node_modules}, matches a file or
 * folder with that name at any depth; everything under a matched folder is excluded too. A pattern
 * with a slash, such as {@code build/tmp} or {@code docs/**}{@code /*.bak}, matches against the
 * path relative to the search folder, and also excludes everything under a matched folder. A
 * leading slash anchors a single name at the search folder's root ({@code /build}).
 */
public final class ExclusionRule {

    private final String pattern;
    private final boolean wholePath;
    private final PathMatcher matcher;

    private ExclusionRule(String pattern, boolean wholePath, PathMatcher matcher) {
        this.pattern = pattern;
        this.wholePath = wholePath;
        this.matcher = matcher;
    }

    /**
     * Parses a pattern typed by the user.
     *
     * @throws InvalidPatternException when the pattern is empty or isn't a valid glob
     */
    public static ExclusionRule parse(String pattern) throws InvalidPatternException {
        Objects.requireNonNull(pattern, "pattern");
        String trimmed = pattern.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidPatternException("The pattern is empty.");
        }
        boolean anchored = trimmed.startsWith("/");
        String glob = trimmed.replaceAll("^/+", "").replaceAll("/+$", "");
        if (glob.isEmpty() || glob.chars().allMatch(c -> c == '*' || c == '/')) {
            throw new InvalidPatternException(
                    "The pattern \"" + trimmed + "\" would exclude everything.");
        }
        boolean wholePath = anchored || glob.contains("/");
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException e) { // includes PatternSyntaxException
            throw new InvalidPatternException(
                    "The pattern \"" + trimmed + "\" is not valid: " + e.getMessage());
        }
        return new ExclusionRule(trimmed, wholePath, matcher);
    }

    /**
     * Whether a path, relative to its search folder, is excluded by this rule. A path is excluded
     * when it or any of its parent folders matches.
     */
    public boolean matches(Path relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("expected a relative path, got " + relativePath);
        }
        for (int depth = 1; depth <= relativePath.getNameCount(); depth++) {
            Path candidate =
                    wholePath ? relativePath.subpath(0, depth) : relativePath.getName(depth - 1);
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** The pattern as the user wrote it. */
    public String getPattern() {
        return pattern;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExclusionRule that && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
