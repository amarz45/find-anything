package io.github.amarz45.findanything.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.amarz45.findanything.privacy.FolderPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Loads and saves the user's settings as one readable JSON file in the app's data folder. Each kind
 * of setting is a section of the file, so saving one leaves the others untouched. Writes replace
 * the file atomically, so a crash can't leave it half-written.
 *
 * <p>The model settings (F13) come from two places. The file holds what the user chose with {@code
 * findany model set}: the backend, the model name and the base URL. The environment ({@code .env}
 * and environment variables, see {@code .env.example}) holds the API key and the defaults for
 * whatever isn't saved. The key is never written to the file.
 */
public final class SettingsStore {

    private static final String INDEX_CONFIG = "indexConfig";
    private static final String MODEL_SETTINGS = "modelSettings";
    private static final String PRIVACY = "privacy";
    private static final String LOCAL_ONLY_FOLDERS = "localOnlyFolders";
    private static final String DISABLED_CATEGORIES = "disabledRedactionCategories";

    /** A value left as in {@code .env.example} counts as not set. */
    private static final Pattern PLACEHOLDER = Pattern.compile("YOUR_[A-Z_]*HERE");

    private final Path settingsFile;
    private final Map<String, String> environment;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** As {@link #SettingsStore(Path, Map)} with an empty environment: no key and no defaults. */
    public SettingsStore(Path settingsFile) {
        this(settingsFile, Map.of());
    }

    /**
     * @param settingsFile the JSON file; its folder is the app's data folder, which is never
     *     indexed
     * @param environment the API key and model defaults, from {@code .env} and the environment
     *     variables ({@link EnvFile#load})
     */
    public SettingsStore(Path settingsFile, Map<String, String> environment) {
        this.settingsFile =
                Objects.requireNonNull(settingsFile, "settingsFile").toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
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

    /**
     * Loads the model settings. Saved values win; the environment fills the model name and base URL
     * that aren't saved, and always supplies the key. When nothing was saved, the cloud backend is
     * used if a key is configured, otherwise the local one.
     *
     * @throws IOException when the file can't be read or the section isn't valid
     */
    public synchronized ModelSettings loadModelSettings() throws IOException {
        JsonNode section = readFile().path(MODEL_SETTINGS);
        if (section.isMissingNode()) {
            BackendType backendType =
                    configured("OPENROUTER_API_KEY").isEmpty()
                            ? BackendType.LOCAL
                            : BackendType.CLOUD;
            return defaultModelSettings(backendType);
        }
        BackendType backendType;
        try {
            backendType = BackendType.valueOf(requireText(section, "backendType"));
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "Settings entry \"backendType\" must be CLOUD or LOCAL: " + section);
        }
        ModelSettings defaults = defaultModelSettings(backendType);
        String modelName = section.path("modelName").asText(defaults.modelName());
        URI baseUrl = defaults.baseUrl();
        if (section.hasNonNull("baseUrl")) {
            try {
                baseUrl = new URI(section.get("baseUrl").asText());
            } catch (URISyntaxException e) {
                throw new IOException(
                        "Settings entry \"baseUrl\" isn't a valid URL: " + e.getMessage());
            }
        }
        return new ModelSettings(backendType, modelName, baseUrl, defaults.apiKey());
    }

    /**
     * The settings for a backend as the environment alone defines them: {@code OPENROUTER_MODEL}
     * and {@code OPENROUTER_API_KEY} for the cloud, {@code OLLAMA_MODEL} and {@code
     * OLLAMA_BASE_URL} for the local backend. A missing model name is empty, and the client reports
     * it.
     */
    public ModelSettings defaultModelSettings(BackendType backendType) {
        return switch (backendType) {
            case CLOUD ->
                    new ModelSettings(
                            BackendType.CLOUD,
                            configured("OPENROUTER_MODEL"),
                            ModelSettings.OPENROUTER_BASE_URL,
                            configured("OPENROUTER_API_KEY"));
            case LOCAL -> {
                String url = configured("OLLAMA_BASE_URL");
                yield new ModelSettings(
                        BackendType.LOCAL,
                        configured("OLLAMA_MODEL"),
                        url.isEmpty() ? ModelSettings.OLLAMA_DEFAULT_BASE_URL : URI.create(url),
                        "");
            }
        };
    }

    /** The environment's value, or empty when it is missing, blank, or still a placeholder. */
    private String configured(String name) {
        String value = environment.getOrDefault(name, "").strip();
        return PLACEHOLDER.matcher(value).matches() ? "" : value;
    }

    /**
     * Saves the backend, model name and base URL, keeping the file's other sections. The API key is
     * never written: it stays in {@code .env} or the environment.
     */
    public synchronized void saveModelSettings(ModelSettings modelSettings) throws IOException {
        ObjectNode root = readFile();
        ObjectNode section = root.putObject(MODEL_SETTINGS);
        section.put("backendType", modelSettings.backendType().name());
        section.put("modelName", modelSettings.modelName());
        section.put("baseUrl", modelSettings.baseUrl().toString());
        writeFile(root);
    }

    /** Loads the "local model only" folders (F14); none when nothing was saved. */
    public synchronized FolderPolicy loadFolderPolicy() throws IOException {
        List<Path> folders = new ArrayList<>();
        for (JsonNode node : readFile().path(PRIVACY).path(LOCAL_ONLY_FOLDERS)) {
            folders.add(Path.of(node.asText()));
        }
        return new FolderPolicy(folders);
    }

    /** Saves the "local model only" folders, keeping the rest of the file. */
    public synchronized void saveFolderPolicy(FolderPolicy folderPolicy) throws IOException {
        ObjectNode root = readFile();
        ArrayNode folders = privacySection(root).putArray(LOCAL_ONLY_FOLDERS);
        folderPolicy.getLocalOnlyFolders().stream()
                .map(Path::toString)
                .sorted()
                .forEach(folders::add);
        writeFile(root);
    }

    /**
     * Loads the redaction categories the user turned off (F14); none when nothing was saved, since
     * every category is on unless turned off.
     */
    public synchronized Set<String> loadDisabledRedactionCategories() throws IOException {
        Set<String> categories = new TreeSet<>();
        for (JsonNode node : readFile().path(PRIVACY).path(DISABLED_CATEGORIES)) {
            categories.add(node.asText());
        }
        return categories;
    }

    /** Saves the redaction categories that are turned off, keeping the rest of the file. */
    public synchronized void saveDisabledRedactionCategories(Set<String> categories)
            throws IOException {
        ObjectNode root = readFile();
        ArrayNode disabled = privacySection(root).putArray(DISABLED_CATEGORIES);
        new TreeSet<>(categories).forEach(disabled::add);
        writeFile(root);
    }

    /** The privacy section, created if the file has none; its two lists are saved separately. */
    private static ObjectNode privacySection(ObjectNode root) {
        return root.get(PRIVACY) instanceof ObjectNode section ? section : root.putObject(PRIVACY);
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
