package io.github.amarz45.findanything.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChangeQueueTest {

    static final Path A = Path.of("a.txt").toAbsolutePath();
    static final Path B = Path.of("b.txt").toAbsolutePath();

    @Test
    void handsOutPathsOnceTheyHaveBeenQuietAndMergesRepeats() throws Exception {
        ChangeQueue queue = new ChangeQueue(Duration.ofMillis(150));
        queue.offer(A);
        queue.offer(Path.of("a.txt")); // same path, relative spelling
        queue.offer(B);
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.takeReady(Duration.ofMillis(20))).isNull(); // nothing quiet yet
        assertThat(queue.takeReady(Duration.ofSeconds(5))).isEqualTo(A); // the oldest first
        assertThat(queue.takeReady(Duration.ofSeconds(5))).isEqualTo(B);
        assertThat(queue.size()).isZero();
        assertThat(queue.takeReady(Duration.ofMillis(20))).isNull();
    }

    @Test
    void aNewChangeRestartsTheQuietPeriod() throws Exception {
        ChangeQueue queue = new ChangeQueue(Duration.ofMillis(200));
        queue.offer(A);
        Thread.sleep(120);
        queue.offer(A); // touched again: not ready for another 200 ms
        assertThat(queue.takeReady(Duration.ofMillis(120))).isNull();
        assertThat(queue.takeReady(Duration.ofSeconds(5))).isEqualTo(A);
    }

    @Test
    void wakesAWaitingTakerWhenAPathIsOffered() throws Exception {
        ChangeQueue queue = new ChangeQueue(Duration.ZERO);
        Thread offerer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            queue.offer(B);
                        });
        offerer.start();
        long started = System.nanoTime();
        assertThat(queue.takeReady(Duration.ofSeconds(5))).isEqualTo(B);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
        offerer.join();
    }
}
