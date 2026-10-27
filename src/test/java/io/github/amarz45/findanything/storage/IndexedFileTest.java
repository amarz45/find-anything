package io.github.amarz45.findanything.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndexedFileTest {

    @Test
    void fileTypeIsTheLowercaseExtensionWithoutTheDot() {
        assertThat(IndexedFile.fileTypeOf(Path.of("a", "Lease-2025.PDF"))).isEqualTo("pdf");
        assertThat(IndexedFile.fileTypeOf(Path.of("notes.tar.gz"))).isEqualTo("gz");
        assertThat(IndexedFile.fileTypeOf(Path.of("README"))).isEmpty();
        assertThat(IndexedFile.fileTypeOf(Path.of(".bashrc"))).isEmpty();
        assertThat(IndexedFile.fileTypeOf(Path.of("trailing."))).isEmpty();
    }

    @Test
    void rejectsNegativeSizes() {
        assertThatThrownBy(
                        () ->
                                new IndexedFile(
                                        Path.of("x.txt"),
                                        "txt",
                                        -1,
                                        Instant.EPOCH,
                                        "",
                                        ExtractionStatus.OK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
