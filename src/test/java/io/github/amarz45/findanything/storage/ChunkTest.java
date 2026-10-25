package io.github.amarz45.findanything.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChunkTest {

    @Test
    void copiesTheEmbeddingSoCallersCannotChangeIt() {
        float[] vector = {1f, 2f};
        Chunk chunk = new Chunk(0, 1, "text", vector);
        vector[0] = 9f;
        chunk.embedding()[1] = 9f;
        assertThat(chunk.embedding()).containsExactly(1f, 2f);
    }

    @Test
    void comparesEmbeddingsByValue() {
        Chunk a = new Chunk(3, 2, "same", new float[] {0.5f});
        Chunk b = new Chunk(3, 2, "same", new float[] {0.5f});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new Chunk(3, 2, "same", new float[0]));
    }

    @Test
    void rejectsNegativePositionsAndPages() {
        assertThatThrownBy(() -> new Chunk(-1, 0, "t", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunk(0, -1, "t", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
