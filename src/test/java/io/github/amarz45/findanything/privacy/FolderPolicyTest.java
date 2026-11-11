package io.github.amarz45.findanything.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FolderPolicyTest {

    static final Path DOCS = Path.of("docs").toAbsolutePath();
    static final Path PRIVATE = DOCS.resolve("private");

    @Test
    void aMarkCoversEverythingUnderTheFolder() {
        FolderPolicy policy = new FolderPolicy();
        assertThat(policy.isLocalOnly(PRIVATE.resolve("bank.txt"))).isFalse();

        policy.setLocalOnly(PRIVATE, true);
        assertThat(policy.isLocalOnly(PRIVATE.resolve("bank.txt"))).isTrue();
        assertThat(policy.isLocalOnly(PRIVATE.resolve("2025/deep/statement.pdf"))).isTrue();
        assertThat(policy.isLocalOnly(PRIVATE)).isTrue();
        // A sibling whose name merely starts the same way isn't inside it.
        assertThat(policy.isLocalOnly(DOCS.resolve("private-notes/idea.txt"))).isFalse();
        assertThat(policy.isLocalOnly(DOCS.resolve("lease.pdf"))).isFalse();
        assertThat(policy.isLocalOnly(DOCS)).isFalse();
    }

    @Test
    void pathsAreComparedAfterNormalizing() {
        FolderPolicy policy = new FolderPolicy();
        policy.setLocalOnly(Path.of("docs", "other", "..", "private"), true);
        assertThat(policy.getLocalOnlyFolders()).containsExactly(PRIVATE);
        assertThat(policy.isLocalOnly(Path.of("docs", "private", ".", "bank.txt"))).isTrue();
    }

    @Test
    void aMarkCanBeRemovedAndTheSnapshotIsACopy() {
        FolderPolicy policy = new FolderPolicy(List.of(PRIVATE, DOCS.resolve("taxes")));
        assertThat(policy.getLocalOnlyFolders())
                .containsExactlyInAnyOrder(PRIVATE, DOCS.resolve("taxes"));

        var snapshot = policy.getLocalOnlyFolders();
        policy.setLocalOnly(PRIVATE, false);
        assertThat(policy.isLocalOnly(PRIVATE.resolve("bank.txt"))).isFalse();
        assertThat(policy.isLocalOnly(DOCS.resolve("taxes/2025.pdf"))).isTrue();
        assertThat(snapshot).hasSize(2);

        policy.setLocalOnly(PRIVATE, false); // removing a mark that isn't there does nothing
        assertThat(policy.getLocalOnlyFolders()).containsExactly(DOCS.resolve("taxes"));
    }
}
