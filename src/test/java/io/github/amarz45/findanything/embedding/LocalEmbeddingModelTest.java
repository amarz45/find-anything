package io.github.amarz45.findanything.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the real model, so it also shows that the native libraries load on this platform. */
class LocalEmbeddingModelTest {

    /**
     * The one folder every test that loads the real model must use: the native library is loaded
     * once per process, so the first model to load decides where it is unpacked.
     */
    static final Path NATIVE_LIBRARY_FOLDER = Path.of(System.getenv("FINDANY_HOME"), "embedding");

    static final String RESUME =
            "Résumé of Jordan Rivera. Work experience: software developer intern. Education:"
                    + " Bachelor of Science in Computer Science. Skills: Java, SQL, Git.";
    static final String LEASE =
            "The tenancy ends on August 31, 2026. The monthly rent is $1,850, due on the first"
                    + " day of each month.";

    static LocalEmbeddingModel model;

    @BeforeAll
    static void createModel() {
        model = new LocalEmbeddingModel(NATIVE_LIBRARY_FOLDER);
    }

    @Test
    void vectorsHaveTheModelsDimensionAndUnitLength() {
        float[] vector = model.embed(List.of(LEASE)).get(0);
        assertThat(vector).hasSize(model.getDimension()).hasSize(384);
        assertThat(cosine(vector, vector)).isCloseTo(1.0, within(1e-4));
        assertThat(dot(vector, vector)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void severalTextsGiveTheirVectorsInTheSameOrderAsOneAtATime() {
        List<float[]> together = model.embed(List.of(RESUME, LEASE, "CV"));
        assertThat(together).hasSize(3);
        assertThat(together.get(0)).isEqualTo(model.embed(List.of(RESUME)).get(0));
        assertThat(together.get(1)).isEqualTo(model.embed(List.of(LEASE)).get(0));
        assertThat(together.get(2)).isEqualTo(model.embed(List.of("CV")).get(0));
    }

    @Test
    void aTextIsCloserToOneWithTheSameMeaningThanToAnUnrelatedOne() {
        List<float[]> vectors = model.embed(List.of("CV", RESUME, LEASE));
        double toResume = cosine(vectors.get(0), vectors.get(1));
        double toLease = cosine(vectors.get(0), vectors.get(2));
        // The résumé never says "CV": no keyword search would find it (the design's F05 example).
        assertThat(toResume).isGreaterThan(toLease + 0.05);
    }

    @Test
    void theNativeLibraryIsUnpackedIntoTheGivenFolder() throws IOException {
        model.embed(List.of("anything"));
        try (Stream<Path> files = Files.walk(NATIVE_LIBRARY_FOLDER)) {
            assertThat(files.filter(Files::isRegularFile))
                    .anyMatch(file -> file.getFileName().toString().contains("tokenizers"));
        }
    }

    @Test
    void anEmptyListGivesNoVectors() {
        assertThat(model.embed(List.of())).isEmpty();
    }

    @Test
    void aBlankTextIsRefused() {
        assertThatThrownBy(() -> model.embed(List.of("   ")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageStartingWith("The embedding model could not embed the text: ");
    }

    @Test
    void theModelIsLoadedOnFirstUseAndOnlyOnce() {
        AtomicInteger loads = new AtomicInteger();
        LocalEmbeddingModel lazy =
                new LocalEmbeddingModel(
                        NATIVE_LIBRARY_FOLDER,
                        () -> {
                            loads.incrementAndGet();
                            return new FixedModel();
                        });
        assertThat(lazy.getDimension()).isEqualTo(384);
        assertThat(loads).hasValue(0);

        assertThat(lazy.embed(List.of("one", "two"))).hasSize(2);
        lazy.embed(List.of("three"));
        assertThat(loads).hasValue(1);
    }

    @Test
    void aFailedLoadIsReportedOnEveryCallWithoutLoadingAgain() {
        AtomicInteger loads = new AtomicInteger();
        LocalEmbeddingModel broken =
                new LocalEmbeddingModel(
                        NATIVE_LIBRARY_FOLDER,
                        () -> {
                            loads.incrementAndGet();
                            // What a missing native library looks like: the library's class fails
                            // to initialize.
                            throw new ExceptionInInitializerError(
                                    new UnsatisfiedLinkError("no tokenizers in java.library.path"));
                        });
        for (int call = 0; call < 2; call++) {
            assertThatThrownBy(() -> broken.embed(List.of("text")))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessage(
                            "The embedding model could not be loaded: no tokenizers in"
                                    + " java.library.path");
        }
        assertThat(loads).hasValue(1);
    }

    /** Stands in for the library's model: every text gets the same vector. */
    private static final class FixedModel
            implements dev.langchain4j.model.embedding.EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(
                    segments.stream().map(segment -> Embedding.from(new float[] {1f})).toList());
        }
    }

    private static double cosine(float[] a, float[] b) {
        return dot(a, b) / Math.sqrt(dot(a, a) * dot(b, b));
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
