package io.github.amarz45.findanything.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15.BgeSmallEnV15EmbeddingModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adapts LangChain4j's in-process embedding model to {@link EmbeddingModel} (the Adapter pattern,
 * F05). The model is BAAI's bge-small-en-v1.5, run on the CPU with ONNX Runtime. It ships inside
 * the library's jar, so nothing is downloaded and no text leaves the computer. The rest of the
 * system never sees the library's types.
 *
 * <p>The model is loaded on the first {@link #embed} call, not in the constructor, so a command
 * that never embeds anything doesn't pay for loading it. A failed load is remembered: every later
 * call fails at once with the same message.
 *
 * <p>Thread-safe. The texts of one call are embedded one after another on the calling thread,
 * because ONNX Runtime already spreads each text's computation over the processor's cores. Handing
 * it several texts at once was no faster (measured: 27 chunks a second one at a time, 20 with one
 * caller per hardware thread, on a 16-core processor).
 */
public final class LocalEmbeddingModel implements EmbeddingModel {

    /** The length of bge-small-en-v1.5's vectors. */
    static final int DIMENSION = 384;

    private final Path nativeLibraryFolder;
    private final Supplier<dev.langchain4j.model.embedding.EmbeddingModel> loader;
    private dev.langchain4j.model.embedding.EmbeddingModel model; // guarded by this
    private EmbeddingException loadFailure; // guarded by this

    /**
     * @param nativeLibraryFolder where the tokenizer's native library is unpacked on first use (a
     *     folder under the app's data folder). The library is loaded once per process, so the first
     *     model to load decides the folder.
     */
    public LocalEmbeddingModel(Path nativeLibraryFolder) {
        // The library would otherwise embed a call's texts on a thread pool of its own; this
        // executor runs each on the calling thread instead.
        this(nativeLibraryFolder, () -> new BgeSmallEnV15EmbeddingModel(Runnable::run));
    }

    /** For tests: a loader that stands in for the library's model, or fails like it. */
    LocalEmbeddingModel(
            Path nativeLibraryFolder,
            Supplier<dev.langchain4j.model.embedding.EmbeddingModel> loader) {
        this.nativeLibraryFolder =
                Objects.requireNonNull(nativeLibraryFolder, "nativeLibraryFolder");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        dev.langchain4j.model.embedding.EmbeddingModel loaded = load();
        try {
            List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
            List<float[]> vectors = new ArrayList<>();
            for (Embedding embedding : loaded.embedAll(segments).content()) {
                vectors.add(embedding.vector());
            }
            return vectors;
        } catch (RuntimeException e) {
            throw new EmbeddingException(
                    "The embedding model could not embed the text: " + reason(e), e);
        }
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }

    private synchronized dev.langchain4j.model.embedding.EmbeddingModel load() {
        if (loadFailure != null) {
            throw loadFailure;
        }
        if (model == null) {
            try {
                Files.createDirectories(nativeLibraryFolder);
                // The tokenizer library has no other way to be configured than these process-wide
                // properties. Left alone, it unpacks its native library into the user's home
                // folder, and on a computer with CUDA it downloads a different build of itself.
                setIfAbsent("DJL_CACHE_DIR", nativeLibraryFolder.toAbsolutePath().toString());
                setIfAbsent("ai.djl.offline", "true");
                model = loader.get();
            } catch (IOException | RuntimeException | LinkageError e) {
                // A missing or unusable native library surfaces as a LinkageError.
                loadFailure =
                        new EmbeddingException(
                                "The embedding model could not be loaded: " + reason(e), e);
                throw loadFailure;
            }
        }
        return model;
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }

    /** The innermost cause's message, which is the one that says what actually went wrong. */
    private static String reason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
