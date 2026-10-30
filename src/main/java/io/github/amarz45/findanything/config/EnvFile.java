package io.github.amarz45.findanything.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads {@code KEY=value} files such as {@code .env}, where the API key and the model defaults live
 * (F13; see {@code .env.example}). Blank lines and {@code #} comments are skipped, an {@code
 * export} prefix is allowed, and one pair of matching quotes around a value is removed. There is no
 * variable expansion. Values are never logged.
 */
public final class EnvFile {

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvFile() {}

    /**
     * Loads the files in order, each overriding the ones before it, and then overlays {@code
     * environment} (the process's environment variables), which overrides them all. A file that
     * doesn't exist is skipped.
     *
     * @throws IOException when a file can't be read or has a line that isn't {@code KEY=value}
     */
    public static Map<String, String> load(Map<String, String> environment, Path... files)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Path file : files) {
            if (Files.isRegularFile(file)) {
                values.putAll(parse(file, Files.readAllLines(file)));
            }
        }
        values.putAll(environment);
        return Map.copyOf(values);
    }

    /** Parses the lines of {@code file}; the file is only named in error messages. */
    static Map<String, String> parse(Path file, List<String> lines) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new IOException(file + ", line " + (i + 1) + ": expected KEY=value.");
            }
            String key = line.substring(0, equals).strip();
            if (!KEY.matcher(key).matches()) {
                throw new IOException(
                        file + ", line " + (i + 1) + ": \"" + key + "\" isn't a valid name.");
            }
            values.put(key, unquote(line.substring(equals + 1).strip()));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
