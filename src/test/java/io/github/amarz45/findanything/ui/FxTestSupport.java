package io.github.amarz45.findanything.ui;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;

/**
 * Runs JavaFX in the tests. The Gradle test task sets {@code glass.platform=headless}, so the real
 * toolkit runs without a display (JavaFX 26 and later). The toolkit is started once per JVM and
 * never exits between test classes.
 */
final class FxTestSupport {

    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONDITION_TIMEOUT = Duration.ofSeconds(180);

    private static boolean started;

    private FxTestSupport() {}

    static synchronized void startToolkit() {
        if (started) {
            return;
        }
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Another test class already started it.
        }
        Platform.setImplicitExit(false);
        started = true;
    }

    /** Runs the action on the JavaFX thread and returns its result, rethrowing what it throws. */
    static <T> T onFxThread(Callable<T> action) throws Exception {
        startToolkit();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Platform.runLater(
                () -> {
                    try {
                        result.set(action.call());
                    } catch (Exception e) {
                        failure.set(e);
                    } finally {
                        done.countDown();
                    }
                });
        if (!done.await(ACTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("The JavaFX thread did not run the action in time.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    static void onFxThread(Runnable action) throws Exception {
        onFxThread(
                () -> {
                    action.run();
                    return null;
                });
    }

    /** Waits until the condition, checked on the JavaFX thread, holds. */
    static void awaitFx(BooleanSupplier condition, Supplier<String> message) throws Exception {
        long deadline = System.nanoTime() + CONDITION_TIMEOUT.toNanos();
        while (!onFxThread(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting: " + message.get());
            }
            Thread.sleep(50);
        }
    }
}
