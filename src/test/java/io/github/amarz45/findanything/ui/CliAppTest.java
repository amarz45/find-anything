package io.github.amarz45.findanything.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.IndexConfig;
import io.github.amarz45.findanything.config.SettingsStore;
import io.github.amarz45.findanything.core.FindAnythingService;
import io.github.amarz45.findanything.indexing.ChangeQueue;
import io.github.amarz45.findanything.indexing.Chunker;
import io.github.amarz45.findanything.indexing.ScriptedExtractor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliAppTest {

    @TempDir Path home;
    Path docs;
    FindAnythingService service;
    CliApp app;

    @BeforeEach
    void setUp() throws Exception {
        docs = home.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("dentist.txt"), "Dentist appointment on March 4, 2026.");
        Files.writeString(
                docs.resolve("lecture.pdf"), "Slide one.\fMerge sort runs in O(n log n) time.");
        Files.writeString(docs.resolve("photo.bin"), "binary");
        Files.writeString(docs.resolve("corrupt.txt"), "bad");
        SettingsStore store = new SettingsStore(home.resolve("app/settings.json"));
        IndexConfig config = store.loadIndexConfig();
        service =
                new FindAnythingService(
                        config,
                        store,
                        new ScriptedExtractor(),
                        new Chunker(300),
                        new ChangeQueue(Duration.ZERO),
                        home.resolve("app/index"),
                        Executors.newFixedThreadPool(2));
        app = new CliApp(service);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    record Run(int exitCode, String out, String err) {}

    Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = app.run(args, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void noArgumentsPrintsHelp() {
        Run run = run();
        assertThat(run.exitCode()).isZero();
        assertThat(run.out())
                .contains("Usage: findany")
                .contains("folders")
                .contains("search")
                .contains("show");
        assertThat(run("folders").out()).contains("Usage: findany folders");
        assertThat(run("bogus").exitCode()).isEqualTo(2);
    }

    @Test
    void addsAFolderThenSearchesAndShows() {
        assertThat(run("folders", "list").out()).contains("No search folders yet");

        Run add = run("folders", "add", docs.toString());
        assertThat(add.exitCode()).isZero();
        assertThat(add.out())
                .contains("Added " + docs)
                .contains("Index up to date: 4 files, 1 failed.");
        assertThat(run("folders", "list").out()).contains(docs.toString());

        Run search = run("search", "merge", "sort");
        assertThat(search.exitCode()).isZero();
        assertThat(search.out())
                .contains("1. " + docs.resolve("lecture.pdf"))
                .contains("page 2: Merge sort runs");

        Run list = run("search");
        assertThat(list.out())
                .contains("dentist.txt")
                .contains("photo.bin")
                .contains("(indexed by name only)")
                .contains("(extraction failed)");

        Run show = run("show", docs.resolve("lecture.pdf").toString(), "--highlight", "n log n");
        assertThat(show.exitCode()).isZero();
        assertThat(show.out())
                .contains("[page 1]")
                .contains("[page 2]")
                .contains("O([[n log n]]) time");
        assertThat(show.err()).contains("1 match for \"n log n\"");

        Run metadataOnly = run("show", docs.resolve("photo.bin").toString());
        assertThat(metadataOnly.out()).contains("No extracted text (indexed by name only)");

        Run status = run("index", "status", "--failed");
        assertThat(status.out())
                .contains("Mode: writer")
                .contains("Files: 4 indexed, 1 failed, 0 pending")
                .contains("corrupt.txt");

        assertThat(run("search", "nothing-matches-this").out()).contains("No results.");
    }

    @Test
    void exclusionsAndErrorsAreReported() {
        run("folders", "add", docs.toString());
        Run exclude = run("exclude", "add", "*.bin");
        assertThat(exclude.exitCode()).isZero();
        assertThat(run("exclude", "list", "--built-in").out())
                .contains("Global: *.bin")
                .contains("Built-in")
                .contains(".ssh");
        assertThat(run("search", "photo").out()).contains("No results.");

        Run bad = run("exclude", "add", "*");
        assertThat(bad.exitCode()).isEqualTo(1);
        assertThat(bad.err()).contains("findany: ").contains("exclude everything");

        Run missing = run("folders", "add", home.resolve("nope").toString());
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.err()).contains("No such file or folder");

        Run covered = run("folders", "add", docs.toString());
        assertThat(covered.exitCode()).isEqualTo(1);
        assertThat(covered.err()).contains("already covered");

        Run gone = run("show", docs.resolve("missing.txt").toString());
        assertThat(gone.exitCode()).isEqualTo(1);
        assertThat(gone.err()).contains("no longer exists");

        Run rebuild = run("index", "rebuild");
        assertThat(rebuild.exitCode()).isZero();
        assertThat(rebuild.out()).contains("Index up to date: 3 files");
        assertThat(run("folders", "remove", docs.toString()).out()).contains("Removed");
        assertThat(run("search").out()).contains("The index is empty");
    }

    @Test
    void resolvesTheDataFolderPerPlatform() {
        assertThat(CliApp.resolveHome(Map.of("FINDANY_HOME", "/tmp/x"), "Linux", "/home/u"))
                .isEqualTo(Path.of("/tmp/x").toAbsolutePath());
        assertThat(CliApp.resolveHome(Map.of(), "Linux", "/home/u"))
                .isEqualTo(Path.of("/home/u/.local/share/find-anything"));
        assertThat(CliApp.resolveHome(Map.of("XDG_DATA_HOME", "/data"), "Linux", "/home/u"))
                .isEqualTo(Path.of("/data/find-anything"));
        assertThat(
                        CliApp.resolveHome(
                                        Map.of("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local"),
                                        "Windows 11",
                                        "C:\\Users\\u")
                                .toString())
                .endsWith("FindAnything");
        assertThat(CliApp.resolveHome(Map.of(), "Mac OS X", "/Users/u"))
                .isEqualTo(Path.of("/Users/u/Library/Application Support/FindAnything"));
        assertThat(CliApp.formatSize(900)).isEqualTo("900 B");
        assertThat(CliApp.formatSize(90_000)).isEqualTo("88 KB");
        assertThat(CliApp.formatSize(3_000_000)).isEqualTo("2.9 MB");
    }
}
