package io.github.amarz45.findanything.embedding;

import java.nio.file.Path;

/**
 * The one {@link LocalEmbeddingModel} that the tests share. Loading the model takes about a second,
 * and its native library is loaded once per process, so the first model to load decides where the
 * library is unpacked: every test that wants the real model takes it from here.
 */
public final class RealEmbeddingModel {

    /** Under the tests' data folder, never the user's. */
    public static final Path NATIVE_LIBRARY_FOLDER =
            Path.of(System.getenv("FINDANY_HOME"), "embedding");

    private static final LocalEmbeddingModel MODEL = new LocalEmbeddingModel(NATIVE_LIBRARY_FOLDER);

    private RealEmbeddingModel() {}

    public static LocalEmbeddingModel get() {
        return MODEL;
    }
}
