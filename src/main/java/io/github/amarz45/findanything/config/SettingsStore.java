package io.github.amarz45.findanything.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads and saves the user's settings as one readable JSON file in the app's data folder. Each kind
 * of setting is a section of the file, so saving one leaves the others untouched. Writes replace
 * the file atomically, so a crash can't leave it half-written.
 */
public final class SettingsStore {

    private static final String INDEX_CONFIG = "indexConfig";

    private final Path settingsFile;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * @param settingsFile the JSON file; its folder is the app's data folder, which is never
     *     indexed
     */
    public SettingsStore(Path settingsFile) {
        this.settingsFile =
                Objects.requireNonNull(settingsFile, "settingsFile").toAbsolutePath().normalize();
    }

    /**
     * Loads the search folders and exclusion rules; an empty configuration when none were saved. A
     * folder whose path no longer exists is marked unavailable.
     *
     * @throws IOException when the file can't be read or isn't valid
     */
    public synchronized IndexConfig loadIndexConfig() throws IOException {
        Path appDataFolder = settingsFile.getParent();
        JsonNode section = readFile().path(INDEX_CONFIG);
        List<SearchFolder> folders = new ArrayList<>();
        for (JsonNode node : section.path("searchFolders")) {
            Path path = Path.of(requireText(node, "path"));
            folders.add(
                    new SearchFolder(
                            path,
                            node.path("paused").asBoolean(false),
                            Files.isDirectory(path),
                            parseRules(node.path("exclusionRules"))));
        }
        return new IndexConfig(appDataFolder, folders, parseRules(section.path("globalRules")));
    }

    /** Saves the search folders and exclusion rules, keeping the file's other sections. */
    public synchronized void saveIndexConfig(IndexConfig indexConfig) throws IOException {
        ObjectNode root = readFile();
        ObjectNode section = root.putObject(INDEX_CONFIG);
        ArrayNode folders = section.putArray("searchFolders");
        for (SearchFolder folder : indexConfig.getSearchFolders()) {
            ObjectNode node = folders.addObject();
            node.put("path", folder.getPath().toString());
            node.put("paused", folder.isPaused());
            putRules(node.putArray("exclusionRules"), folder.getExclusionRules());
        }
        putRules(section.putArray("globalRules"), indexConfig.getGlobalRules());
        writeFile(root);
    }

    private static List<ExclusionRule> parseRules(JsonNode array) throws IOException {
        List<ExclusionRule> rules = new ArrayList<>();
        for (JsonNode node : array) {
            try {
                rules.add(ExclusionRule.parse(node.asText()));
            } catch (InvalidPatternException e) {
                throw new IOException("Invalid exclusion pattern in settings: " + e.getMessage());
            }
        }
        return rules;
    }

    private static void putRules(ArrayNode array, List<ExclusionRule> rules) {
        rules.forEach(rule -> array.add(rule.getPattern()));
    }

    private static String requireText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("Settings entry is missing \"" + field + "\": " + node);
        }
        return value.asText();
    }

    private ObjectNode readFile() throws IOException {
        if (!Files.exists(settingsFile)) {
            return mapper.createObjectNode();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(settingsFile));
        } catch (IOException e) {
            throw new IOException(
                    "The settings file " + settingsFile + " isn't valid JSON: " + e.getMessage(),
                    e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return mapper.createObjectNode();
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IOException("The settings file " + settingsFile + " isn't a JSON object.");
        }
        return object;
    }

    private void writeFile(ObjectNode root) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Path temp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        Files.writeString(temp, mapper.writeValueAsString(root) + System.lineSeparator());
        try {
            Files.move(
                    temp,
                    settingsFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path getSettingsFile() {
        return settingsFile;
    }
}
