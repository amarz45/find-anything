package io.github.amarz45.findanything.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amarz45.findanything.privacy.PrivacyLogEntry.Excerpt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyLogTest {

    static final Instant NOON = Instant.parse("2026-11-09T12:00:00Z");
    static final Path LEASE = Path.of("docs", "lease.pdf").toAbsolutePath();

    @TempDir Path home;

    static PrivacyLogEntry entry(int minute, String text) {
        return new PrivacyLogEntry(
                NOON.plusSeconds(60L * minute),
                "vendor/model",
                List.of(new Excerpt("user", List.of(), text)),
                List.of());
    }

    @Test
    void isEmptyUntilSomethingIsSent() throws Exception {
        PrivacyLog log = new PrivacyLog(home.resolve("data/privacy-log.jsonl"));
        assertThat(log.getEntries(10)).isEmpty();
        assertThat(log.getLogFile()).isEqualTo(home.resolve("data/privacy-log.jsonl"));
        assertThat(log.getLogFile()).doesNotExist();
    }

    @Test
    void roundTripsAnEntryWithEveryField() throws Exception {
        PrivacyLog log = new PrivacyLog(home.resolve("data/privacy-log.jsonl"));
        PrivacyLogEntry sent =
                new PrivacyLogEntry(
                        NOON,
                        "anthropic/some-model",
                        List.of(
                                new Excerpt(
                                        "system", List.of(), "You answer from the user's files."),
                                new Excerpt(
                                        "user", List.of(), "When does my lease end?\nThanks — é"),
                                new Excerpt(
                                        "tool",
                                        List.of(LEASE),
                                        "Card: [redacted: card-number]\n\"quoted\" \\ text")),
                        List.of("card-number"));
        log.append(sent);

        assertThat(log.getEntries(5)).containsExactly(sent);
        // The folder was created, and the entry is one line however many lines its text has.
        assertThat(Files.readAllLines(log.getLogFile())).hasSize(1);
    }

    @Test
    void returnsTheNewestFirstUpToTheLimit() throws Exception {
        PrivacyLog log = new PrivacyLog(home.resolve("privacy-log.jsonl"));
        log.append(entry(1, "first"));
        log.append(entry(2, "second"));
        log.append(entry(3, "third"));

        assertThat(log.getEntries(2)).containsExactly(entry(3, "third"), entry(2, "second"));
        assertThat(log.getEntries(100))
                .containsExactly(entry(3, "third"), entry(2, "second"), entry(1, "first"));
        // Another PrivacyLog on the same file, as after a restart, sees the same entries.
        assertThat(new PrivacyLog(home.resolve("privacy-log.jsonl")).getEntries(1))
                .containsExactly(entry(3, "third"));
        assertThatThrownBy(() -> log.getEntries(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsADamagedLineAndKeepsTheRest() throws Exception {
        Path file = home.resolve("privacy-log.jsonl");
        PrivacyLog log = new PrivacyLog(file);
        log.append(entry(1, "first"));
        Files.writeString(file, Files.readString(file) + "{ not json\n\n{\"modelName\":\"m\"}\n");
        log.append(entry(2, "second"));

        assertThat(log.getEntries(10)).containsExactly(entry(2, "second"), entry(1, "first"));
    }

    @Test
    void reportsALogThatCannotBeWritten() throws Exception {
        // A file where the log's folder should be.
        Files.writeString(home.resolve("blocked"), "not a folder");
        PrivacyLog log = new PrivacyLog(home.resolve("blocked/privacy-log.jsonl"));
        assertThatThrownBy(() -> log.append(entry(1, "first"))).isInstanceOf(IOException.class);
    }
}
