package io.github.amarz45.findanything.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileTest {

    @TempDir Path folder;

    @Test
    void parsesCommentsBlanksExportsAndQuotes() throws Exception {
        Map<String, String> values =
                EnvFile.parse(
                        Path.of(".env"),
                        List.of(
                                "# Cloud model",
                                "",
                                "OPENROUTER_MODEL=anthropic/claude-sonnet-4.5",
                                "export OLLAMA_MODEL = \"llama3.1:8b\"",
                                "OLLAMA_BASE_URL='http://desktop:11434'",
                                "EMPTY=",
                                "EQUALS=a=b"));
        assertThat(values)
                .containsEntry("OPENROUTER_MODEL", "anthropic/claude-sonnet-4.5")
                .containsEntry("OLLAMA_MODEL", "llama3.1:8b")
                .containsEntry("OLLAMA_BASE_URL", "http://desktop:11434")
                .containsEntry("EMPTY", "")
                .containsEntry("EQUALS", "a=b")
                .hasSize(5);
    }

    @Test
    void laterFilesOverrideEarlierOnesAndTheEnvironmentOverridesBoth() throws Exception {
        Path home = folder.resolve("home.env");
        Path cwd = folder.resolve("cwd.env");
        Files.writeString(home, "OLLAMA_MODEL=from-home\r\nOPENROUTER_MODEL=from-home\r\n");
        Files.writeString(cwd, "OLLAMA_MODEL=from-cwd\n");
        Map<String, String> values =
                EnvFile.load(Map.of("OPENROUTER_MODEL", "from-env"), home, cwd);
        assertThat(values)
                .containsEntry("OLLAMA_MODEL", "from-cwd")
                .containsEntry("OPENROUTER_MODEL", "from-env");
    }

    @Test
    void skipsMissingFiles() throws Exception {
        assertThat(EnvFile.load(Map.of("A", "1"), folder.resolve("missing"))).containsOnlyKeys("A");
    }

    @Test
    void reportsBadLinesWithTheirNumber() throws Exception {
        Path file = folder.resolve(".env");
        Files.writeString(file, "A=1\nnot a pair\n");
        assertThatThrownBy(() -> EnvFile.load(Map.of(), file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining(".env");
        Files.writeString(file, "1A=x\n");
        assertThatThrownBy(() -> EnvFile.load(Map.of(), file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("1A");
    }
}
