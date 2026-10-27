package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
