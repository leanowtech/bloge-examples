package com.leanowtech.bloge.gateway.visual;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Starts one browser session behind a caller-enforced wall-clock boundary.
 *
 * <p>Selenium's HTTP timeout and JUnit's test timeout are not sufficient when a driver handshake
 * becomes stuck in an uninterruptible client wait. This launcher owns a single daemon platform
 * thread with no queue, returns at the configured deadline, invokes the driver-service abort hook,
 * and cleans up a session that races with timeout. A non-cooperative factory may linger only on the
 * daemon thread and therefore cannot keep the Maven test JVM alive.</p>
 *
 * <p>The launcher deliberately exposes only a closed disposition. Provider exception text is not
 * copied into its failure, so local paths, command lines, or browser diagnostics cannot leak into
 * build summaries.</p>
 */
final class BoundedBrowserSessionLauncher {

    private static final AtomicLong LAUNCHER_SEQUENCE = new AtomicLong();
    private static final AtomicLong CLEANUP_SEQUENCE = new AtomicLong();
    private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(60);

    private BoundedBrowserSessionLauncher() {
    }

    /**
     * Starts one resource or fails within the supplied deadline.
     *
     * @param timeout caller-owned launch deadline from 100 milliseconds through 60 seconds
     * @param factory potentially blocking browser-session factory
     * @param abort best-effort driver-service termination invoked after failed ownership transfer
     * @param cleanup best-effort cleanup for a resource produced concurrently with abandonment
     * @param <T> owned browser-session type
     * @return the uniquely owned session
     * @throws LaunchException when the deadline, caller interruption, or factory failure prevents
     *         ownership transfer
     */
    static <T> T launch(
            Duration timeout,
            Callable<T> factory,
            Runnable abort,
            Consumer<T> cleanup) {
        Duration boundedTimeout = boundedTimeout(timeout);
        Callable<T> requiredFactory = Objects.requireNonNull(factory, "factory");
        Runnable requiredAbort = Objects.requireNonNull(abort, "abort");
        Consumer<T> requiredCleanup = Objects.requireNonNull(cleanup, "cleanup");
        LaunchState<T> state = new LaunchState<>();
        long launcherId = LAUNCHER_SEQUENCE.incrementAndGet();
        ThreadFactory threadFactory = task -> Thread.ofPlatform()
                .daemon(true)
                .name("resource-gateway-browser-session-launch-" + launcherId)
                .unstarted(task);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        Future<T> future = executor.submit(() -> {
            T resource = Objects.requireNonNull(requiredFactory.call(), "browser session");
            if (state.publish(resource)) {
                return resource;
            }
            cleanupQuietly(requiredCleanup, resource);
            throw new CancellationException();
        });
        try {
            return future.get(boundedTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            abandon(state, future, requiredAbort, requiredCleanup);
            throw new LaunchException(Disposition.TIMED_OUT);
        } catch (InterruptedException callerInterrupted) {
            abandon(state, future, requiredAbort, requiredCleanup);
            Thread.currentThread().interrupt();
            throw new LaunchException(Disposition.CALLER_INTERRUPTED);
        } catch (ExecutionException | CancellationException factoryFailure) {
            abandon(state, future, requiredAbort, requiredCleanup);
            throw new LaunchException(Disposition.FACTORY_FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> void abandon(
            LaunchState<T> state,
            Future<T> future,
            Runnable abort,
            Consumer<T> cleanup) {
        T published = state.abandon();
        future.cancel(true);
        runBestEffort("abort", () -> abortQuietly(abort));
        if (published != null) {
            runBestEffort("cleanup", () -> cleanupQuietly(cleanup, published));
        }
    }

    /**
     * Starts non-blocking best-effort cleanup after setup fails post-launch.
     *
     * <p>Abort and session cleanup use independent daemon threads so either uncooperative hook
     * cannot hold the JUnit caller or suppress the other cleanup attempt.</p>
     *
     * @param resource session whose ownership is being abandoned; may be {@code null}
     * @param abort driver-service termination hook
     * @param cleanup session cleanup hook
     * @param <T> browser-session type
     */
    static <T> void cleanupAfterFailedLaunch(
            T resource,
            Runnable abort,
            Consumer<T> cleanup) {
        Runnable requiredAbort = Objects.requireNonNull(abort, "abort");
        Consumer<T> requiredCleanup = Objects.requireNonNull(cleanup, "cleanup");
        runBestEffort("abort", () -> abortQuietly(requiredAbort));
        if (resource != null) {
            runBestEffort("cleanup", () -> cleanupQuietly(requiredCleanup, resource));
        }
    }

    private static Duration boundedTimeout(Duration timeout) {
        Duration required = Objects.requireNonNull(timeout, "timeout");
        if (required.compareTo(MINIMUM_TIMEOUT) < 0
                || required.compareTo(MAXIMUM_TIMEOUT) > 0
                || !required.equals(Duration.ofMillis(required.toMillis()))) {
            throw new IllegalArgumentException("Browser session launch timeout is invalid");
        }
        return required;
    }

    private static void abortQuietly(Runnable abort) {
        try {
            abort.run();
        } catch (RuntimeException ignored) {
            // The launch result remains failed; provider diagnostics must not escape this boundary.
        }
    }

    private static <T> void cleanupQuietly(Consumer<T> cleanup, T resource) {
        try {
            cleanup.accept(resource);
        } catch (RuntimeException ignored) {
            // Cleanup is best effort after ownership has already been abandoned.
        }
    }

    private static void runBestEffort(String purpose, Runnable operation) {
        Thread.ofPlatform()
                .daemon(true)
                .name("resource-gateway-browser-session-" + purpose + '-'
                        + CLEANUP_SEQUENCE.incrementAndGet())
                .start(operation);
    }

    /** Stable browser-session launch outcome that is safe to expose in test summaries. */
    enum Disposition {
        /** The caller-owned wall-clock deadline elapsed. */
        TIMED_OUT,

        /** The waiting test thread was interrupted and its interrupt flag was restored. */
        CALLER_INTERRUPTED,

        /** The factory failed, returned null, or was cancelled before ownership transfer. */
        FACTORY_FAILED
    }

    /** Bounded failure that intentionally omits the browser or driver-service exception. */
    static final class LaunchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final Disposition disposition;

        private LaunchException(Disposition disposition) {
            super("Browser session launch was "
                    + Objects.requireNonNull(disposition, "disposition")
                    .name().toLowerCase(java.util.Locale.ROOT));
            this.disposition = disposition;
        }

        /**
         * Returns the closed launch outcome.
         *
         * @return timeout, caller interruption, or factory failure
         */
        Disposition disposition() {
            return disposition;
        }
    }

    private static final class LaunchState<T> {
        private boolean abandoned;
        private T published;

        private synchronized boolean publish(T resource) {
            if (abandoned) {
                return false;
            }
            published = resource;
            return true;
        }

        private synchronized T abandon() {
            abandoned = true;
            T value = published;
            published = null;
            return value;
        }
    }
}
