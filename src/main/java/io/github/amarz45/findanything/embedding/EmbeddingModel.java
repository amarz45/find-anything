package io.github.amarz45.findanything.embedding;

import java.util.List;

/**
 * Turns text into vectors, so that texts with a similar meaning get vectors that are close together
 * (F05). The target interface of the Adapter pattern: the indexer and the search engine see only
 * this, in the system's own terms. Implementations are safe to call from several threads at once.
 */
public interface EmbeddingModel {

    /**
     * Embeds each text. The same model gives the same vector for the same text.
     *
     * @param texts the texts to embed; none may be blank
     * @return one vector of {@link #getDimension()} numbers per text, in the same order
     * @throws EmbeddingException when the model can't be loaded or can't embed a text
     */
    List<float[]> embed(List<String> texts);

    /** The length of every vector this model returns. */
    int getDimension();
}
