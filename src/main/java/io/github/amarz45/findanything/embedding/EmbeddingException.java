package io.github.amarz45.findanything.embedding;

/**
 * The embedding model couldn't be loaded or couldn't embed a text (F05). Callers fall back to
 * working without vectors: the search engine to keyword search only, the indexer to leaving the
 * content without embeddings. The message is written for the user and the log.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
