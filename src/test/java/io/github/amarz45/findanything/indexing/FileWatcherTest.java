package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amarz45.findanything.config.IndexConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the watcher records for a change. The methods are called directly, so no test waits for the
 * operating system to report one.
 */
class FileWatcherTest {

    @TempDir Path home;
    Path docs;
    ChangeQueue queue;
    FileWatcher fileWatcher;

    @BeforeEach
    void setUp() throws Exception {
        docs = home.resolve("docs");
        Files.createDirectories(docs);
        IndexConfig config = new IndexConfig(home.resolve("app"));
        config.addFolder(docs);
        queue = new ChangeQueue(Duration.ZERO);
        fileWatcher = new FileWatcher(config, queue);
    }

    Path next() throws InterruptedException {
        return queue.takeReady(Duration.ZERO);
    }

    @Test
    void aChangedFileIsRecorded() throws Exception {
        fileWatcher.onChange(docs.resolve("sub/notes.txt"), false, false);
        assertThat(next()).isEqualTo(docs.resolve("sub/notes.txt"));
        assertThat(queue.size()).isZero();
    }

    @Test
    void aDeletedFileIsRecorded() throws Exception {
        fileWatcher.onChange(docs.resolve("gone.txt"), false, true);
        assertThat(next()).isEqualTo(docs.resolve("gone.txt"));
    }

    @Test
    void anExcludedFileIsIgnored() {
        fileWatcher.onChange(docs.resolve("scratch.tmp"), false, false);
        fileWatcher.onChange(docs.resolve(".ssh/id_ed25519"), false, false);
        fileWatcher.onChange(docs.resolve("node_modules/package/readme.md"), false, true);
        assertThat(queue.size()).isZero();
    }

    @Test
    void aFileOutsideEverySearchFolderIsIgnored() {
        fileWatcher.onChange(home.resolve("elsewhere/notes.txt"), false, false);
        assertThat(queue.size()).isZero();
    }

    @Test
    void aCreatedOrChangedFolderIsIgnoredAndADeletedOneIsRecorded() throws Exception {
        fileWatcher.onChange(docs.resolve("sub"), true, false);
        assertThat(queue.size()).isZero();
        fileWatcher.onChange(docs.resolve("sub"), true, true);
        assertThat(next()).isEqualTo(docs.resolve("sub"));
    }

    @Test
    void lostEventsRecordTheWholeSearchFolder() throws Exception {
        fileWatcher.onOverflow(docs);
        assertThat(next()).isEqualTo(docs);
    }
}
