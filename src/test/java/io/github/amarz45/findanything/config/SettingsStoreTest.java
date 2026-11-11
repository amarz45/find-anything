package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.privacy.FolderPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    static final Path CORPUS = Path.of("testdata", "corpus").toAbsolutePath();

    @TempDir Path home;

    @Test
    void loadsAnEmptyConfigurationWhenNothingWasSaved() throws Exception {
        IndexConfig config = new SettingsStore(home.resolve("settings.json")).loadIndexConfig();
        assertThat(config.getSearchFolders()).isEmpty();
        assertThat(config.getGlobalRules()).isEmpty();
        assertThat(config.getAppDataFolder()).isEqualTo(home.toAbsolutePath().normalize());
    }

    @Test
    void roundTripsFoldersRulesAndPausedState() throws Exception {
        SettingsStore store = new SettingsStore(home.resolve("settings.json"));
        IndexConfig config = store.loadIndexConfig();
        SearchFolder folder = config.addFolder(CORPUS);
        folder.addRule(ExclusionRule.parse("*.csv"));
        folder.setPaused(true);
        config.addGlobalRule(ExclusionRule.parse("node_modules"));
        store.saveIndexConfig(config);

        IndexConfig loaded = store.loadIndexConfig();
        assertThat(loaded.getSearchFolders()).hasSize(1);
        SearchFolder loadedFolder = loaded.getSearchFolders().get(0);
        assertThat(loadedFolder.getPath()).isEqualTo(CORPUS);
        assertThat(loadedFolder.isPaused()).isTrue();
        assertThat(loadedFolder.isAvailable()).isTrue();
        assertThat(loadedFolder.getExclusionRules()).containsExactly(ExclusionRule.parse("*.csv"));
        assertThat(loaded.getGlobalRules()).containsExactly(ExclusionRule.parse("node_modules"));
        assertThat(Files.readString(home.resolve("settings.json"))).contains("\"indexConfig\"");
    }

    @Test
    void keepsOtherSectionsAndMarksMissingFoldersUnavailable() throws Exception {
        Path file = home.resolve("settings.json");
        Files.writeString(
                file,
                """
                { "modelSettings": { "backendType": "LOCAL" },
                  "indexConfig": { "searchFolders": [ { "path": "%s" } ] } }
                """
                        .formatted(home.resolve("missing").toString().replace("\\", "\\\\")));
        SettingsStore store = new SettingsStore(file);
        IndexConfig config = store.loadIndexConfig();
        assertThat(config.getSearchFolders().get(0).isAvailable()).isFalse();
        store.saveIndexConfig(config);
        assertThat(Files.readString(file)).contains("\"modelSettings\"").contains("LOCAL");
    }

    @Test
    void reportsCorruptFiles() throws Exception {
        Path file = home.resolve("settings.json");
        Files.writeString(file, "{ not json");
        assertThatThrownBy(() -> new SettingsStore(file).loadIndexConfig())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("isn't valid JSON");
        Files.writeString(file, "{ \"indexConfig\": { \"globalRules\": [\"*\"] } }");
        assertThatThrownBy(() -> new SettingsStore(file).loadIndexConfig())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid exclusion pattern");
    }

    static final Map<String, String> CLOUD_ENV =
            Map.of(
                    "OPENROUTER_API_KEY", "sk-or-test-key",
                    "OPENROUTER_MODEL", "vendor/model",
                    "OLLAMA_MODEL", "llama",
                    "OLLAMA_BASE_URL", "http://desktop:11434");

    @Test
    void defaultsToTheCloudWhenAKeyIsConfiguredAndToLocalOtherwise() throws Exception {
        ModelSettings cloud =
                new SettingsStore(home.resolve("settings.json"), CLOUD_ENV).loadModelSettings();
        assertThat(cloud.backendType()).isEqualTo(BackendType.CLOUD);
        assertThat(cloud.modelName()).isEqualTo("vendor/model");
        assertThat(cloud.baseUrl()).isEqualTo(ModelSettings.OPENROUTER_BASE_URL);
        assertThat(cloud.apiKey()).isEqualTo("sk-or-test-key");

        ModelSettings local =
                new SettingsStore(
                                home.resolve("settings.json"),
                                Map.of("OLLAMA_MODEL", "llama", "OLLAMA_BASE_URL", "http://d:1"))
                        .loadModelSettings();
        assertThat(local.backendType()).isEqualTo(BackendType.LOCAL);
        assertThat(local.modelName()).isEqualTo("llama");
        assertThat(local.baseUrl()).isEqualTo(java.net.URI.create("http://d:1"));
        assertThat(local.hasApiKey()).isFalse();

        ModelSettings bare = new SettingsStore(home.resolve("settings.json")).loadModelSettings();
        assertThat(bare.backendType()).isEqualTo(BackendType.LOCAL);
        assertThat(bare.modelName()).isEmpty();
        assertThat(bare.baseUrl()).isEqualTo(ModelSettings.OLLAMA_DEFAULT_BASE_URL);
    }

    @Test
    void ignoresThePlaceholdersFromTheExampleFile() throws Exception {
        SettingsStore store =
                new SettingsStore(
                        home.resolve("settings.json"),
                        Map.of(
                                "OPENROUTER_API_KEY", "YOUR_OPENROUTER_API_KEY_HERE",
                                "OPENROUTER_MODEL", "YOUR_MODEL_ID_HERE",
                                "OLLAMA_MODEL", "YOUR_OLLAMA_MODEL_HERE"));
        assertThat(store.loadModelSettings().backendType()).isEqualTo(BackendType.LOCAL);
        assertThat(store.defaultModelSettings(BackendType.CLOUD).hasApiKey()).isFalse();
        assertThat(store.defaultModelSettings(BackendType.CLOUD).modelName()).isEmpty();
        assertThat(store.defaultModelSettings(BackendType.LOCAL).modelName()).isEmpty();
    }

    @Test
    void roundTripsModelSettingsWithoutWritingTheKey() throws Exception {
        Path file = home.resolve("settings.json");
        SettingsStore store = new SettingsStore(file, CLOUD_ENV);
        store.saveModelSettings(
                new ModelSettings(
                        BackendType.CLOUD, "other/model", ModelSettings.OPENROUTER_BASE_URL, "k"));
        String json = Files.readString(file);
        assertThat(json).contains("\"modelSettings\"").contains("CLOUD").contains("other/model");
        assertThat(json).doesNotContain("sk-or-test-key").doesNotContain("apiKey");

        ModelSettings loaded = store.loadModelSettings();
        assertThat(loaded.modelName()).isEqualTo("other/model");
        assertThat(loaded.apiKey()).isEqualTo("sk-or-test-key");
        assertThat(loaded)
                .isEqualTo(
                        new ModelSettings(
                                BackendType.CLOUD,
                                "other/model",
                                ModelSettings.OPENROUTER_BASE_URL,
                                "sk-or-test-key"));
    }

    @Test
    void savedModelSettingsWinOverTheEnvironmentAndKeepTheIndexConfig() throws Exception {
        Path file = home.resolve("settings.json");
        SettingsStore store = new SettingsStore(file, CLOUD_ENV);
        IndexConfig config = store.loadIndexConfig();
        config.addFolder(CORPUS);
        store.saveIndexConfig(config);
        store.saveModelSettings(
                new ModelSettings(
                        BackendType.LOCAL,
                        "mistral",
                        java.net.URI.create("http://laptop:11434"),
                        ""));

        ModelSettings loaded = store.loadModelSettings();
        assertThat(loaded.backendType()).isEqualTo(BackendType.LOCAL);
        assertThat(loaded.modelName()).isEqualTo("mistral");
        assertThat(loaded.baseUrl()).isEqualTo(java.net.URI.create("http://laptop:11434"));
        assertThat(store.loadIndexConfig().getSearchFolders()).hasSize(1);

        Files.writeString(file, "{ \"modelSettings\": { \"backendType\": \"LOCAL\" } }");
        ModelSettings partial = store.loadModelSettings();
        assertThat(partial.modelName()).isEqualTo("llama");
        assertThat(partial.baseUrl()).isEqualTo(java.net.URI.create("http://desktop:11434"));
    }

    @Test
    void reportsInvalidModelSettings() throws Exception {
        Path file = home.resolve("settings.json");
        Files.writeString(file, "{ \"modelSettings\": { \"backendType\": \"REMOTE\" } }");
        assertThatThrownBy(() -> new SettingsStore(file).loadModelSettings())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CLOUD or LOCAL");
        Files.writeString(
                file,
                "{ \"modelSettings\": { \"backendType\": \"LOCAL\", \"baseUrl\": \":bad\" } }");
        assertThatThrownBy(() -> new SettingsStore(file).loadModelSettings())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("valid URL");
    }

    @Test
    void hasNoPrivacySettingsUntilTheyAreSaved() throws Exception {
        SettingsStore store = new SettingsStore(home.resolve("settings.json"));
        assertThat(store.loadFolderPolicy().getLocalOnlyFolders()).isEmpty();
        assertThat(store.loadDisabledRedactionCategories()).isEmpty();
    }

    @Test
    void roundTripsLocalOnlyFoldersAndDisabledCategoriesSeparately() throws Exception {
        Path file = home.resolve("settings.json");
        SettingsStore store = new SettingsStore(file);
        Path privateFolder = CORPUS.resolve("private");

        FolderPolicy policy = new FolderPolicy();
        policy.setLocalOnly(privateFolder, true);
        policy.setLocalOnly(CORPUS.resolve("housing"), true);
        store.saveFolderPolicy(policy);
        store.saveDisabledRedactionCategories(Set.of("sin", "api-key"));

        // Saving one list keeps the other, and a fresh store reads both back.
        SettingsStore reopened = new SettingsStore(file);
        assertThat(reopened.loadFolderPolicy().getLocalOnlyFolders())
                .containsExactlyInAnyOrder(privateFolder, CORPUS.resolve("housing"));
        assertThat(
                        reopened.loadFolderPolicy()
                                .isLocalOnly(privateFolder.resolve("bank-details.txt")))
                .isTrue();
        assertThat(reopened.loadDisabledRedactionCategories()).containsExactly("api-key", "sin");

        policy.setLocalOnly(privateFolder, false);
        store.saveFolderPolicy(policy);
        store.saveDisabledRedactionCategories(Set.of());
        assertThat(reopened.loadFolderPolicy().getLocalOnlyFolders())
                .containsExactly(CORPUS.resolve("housing"));
        assertThat(reopened.loadDisabledRedactionCategories()).isEmpty();
    }

    @Test
    void privacySettingsKeepTheOtherSectionsAndAreKeptByThem() throws Exception {
        Path file = home.resolve("settings.json");
        SettingsStore store = new SettingsStore(file, CLOUD_ENV);
        IndexConfig config = store.loadIndexConfig();
        config.addFolder(CORPUS);
        store.saveIndexConfig(config);
        store.saveModelSettings(store.loadModelSettings());

        FolderPolicy policy = new FolderPolicy(List.of(CORPUS.resolve("private")));
        store.saveFolderPolicy(policy);
        store.saveDisabledRedactionCategories(Set.of("sin"));
        assertThat(store.loadIndexConfig().getSearchFolders()).hasSize(1);
        assertThat(Files.readString(file))
                .contains("\"indexConfig\"")
                .contains("\"modelSettings\"")
                .contains("\"privacy\"");

        // The other sections' saves leave the privacy section alone.
        store.saveIndexConfig(config);
        store.saveModelSettings(store.loadModelSettings());
        assertThat(store.loadFolderPolicy().getLocalOnlyFolders())
                .containsExactly(CORPUS.resolve("private"));
        assertThat(store.loadDisabledRedactionCategories()).containsExactly("sin");
    }
}
