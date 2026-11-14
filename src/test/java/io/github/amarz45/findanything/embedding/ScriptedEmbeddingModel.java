package io.github.amarz45.findanything.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link EmbeddingModel}, so tests need no real model. A text given a vector with
 * {@link #with} gets that vector. Every other text gets a vector made from the text alone: the same
 * text always gets the same one, and two different texts get vectors that are nearly unrelated
 * (their cosine similarity stays close to 0), so unscripted texts never look alike by accident. It
 * records every call and can be told to fail. Thread-safe.
 */
public final class ScriptedEmbeddingModel implements EmbeddingModel {

    /** Long enough that two unscripted texts' vectors are nearly unrelated. */
    public static final int DIMENSION = 128;

    private final Map<String, float[]> scripted = new ConcurrentHashMap<>();
    private final List<List<String>> calls = new CopyOnWriteArrayList<>();
    private volatile String failure;
    private volatile String failingWord;

    /** Gives a text a chosen vector; a vector shorter than the dimension is filled with zeros. */
    public ScriptedEmbeddingModel with(String text, float... vector) {
        if (vector.length > DIMENSION) {
            throw new IllegalArgumentException("At most " + DIMENSION + " numbers.");
        }
        float[] padded = new float[DIMENSION];
        System.arraycopy(vector, 0, padded, 0, vector.length);
        scripted.put(text, padded);
        return this;
    }

    /** Makes every call fail with this message, as when the model can't be loaded. */
    public void failWith(String message) {
        this.failure = message;
    }

    /** Makes a call fail when one of its texts contains the word; other calls work. */
    public void failOnTextContaining(String word) {
        this.failingWord = word;
    }

    /** Every call's texts so far, in order. */
    public List<List<String>> calls() {
        return List.copyOf(calls);
    }

    /** How many times the text has been embedded. */
    public long timesEmbedded(String text) {
        return calls.stream().flatMap(List::stream).filter(text::equals).count();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        calls.add(List.copyOf(texts));
        String message = failure;
        if (message != null) {
            throw new EmbeddingException(message, null);
        }
        String word = failingWord;
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            if (word != null && text.contains(word)) {
                throw new EmbeddingException("The scripted model refuses \"" + word + "\".", null);
            }
            float[] vector = scripted.get(text);
            vectors.add(vector != null ? vector.clone() : vectorOf(text));
        }
        return vectors;
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }

    /** A unit vector that depends only on the text. */
    private static float[] vectorOf(String text) {
        Random random = new Random(text.hashCode());
        float[] vector = new float[DIMENSION];
        double sumOfSquares = 0;
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (float) random.nextGaussian();
            sumOfSquares += vector[i] * vector[i];
        }
        float length = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] /= length;
        }
        return vector;
    }
}
