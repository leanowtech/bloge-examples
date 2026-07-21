package com.leanowtech.bloge.gateway.visual;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Closes one browser session through bounded graceful and forced phases. */
final class BoundedBrowserSessionCloser {

    private static final AtomicLong CLOSER_SEQUENCE = new AtomicLong();
    private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(60);

    private BoundedBrowserSessionCloser() {
    }

    /**
     * Closes a browser session without allowing either provider hook to hold the caller forever.
     *
     * <p>Each phase receives the full timeout independently, so the total caller boundary is at
     * most twice the supplied duration. A failed or timed-out graceful phase is not itself a test
     * failure when the independent force-close phase proves that it completed.</p>
     *
     * @param timeout per-phase wall-clock timeout from 100 milliseconds through 60 seconds
     * @param gracefulClose ordinary WebDriver protocol shutdown
     * @param forceClose independently owned process-level fallback
     * @return whether graceful or forced shutdown completed
     * @throws CloseException when the caller is interrupted or forced shutdown cannot complete
     */
    static Disposition close(Duration timeout, Runnable gracefulClose, Runnable forceClose) {
        Duration boundedTimeout = boundedTimeout(timeout);
        Runnable requiredGracefulClose = Objects.requireNonNull(gracefulClose, "gracefulClose");
        Runnable requiredForceClose = Objects.requireNonNull(forceClose, "forceClose");

        HookOutcome gracefulOutcome = runBounded(
                "graceful", boundedTimeout, requiredGracefulClose);
        if (gracefulOutcome == HookOutcome.COMPLETED) {
            return Disposition.GRACEFUL;
        }
        if (gracefulOutcome == HookOutcome.CALLER_INTERRUPTED) {
            runBestEffortForce(requiredForceClose);
            throw new CloseException(FailureDisposition.CALLER_INTERRUPTED);
        }

        HookOutcome forceOutcome = runBounded("force", boundedTimeout, requiredForceClose);
        if (forceOutcome == HookOutcome.COMPLETED) {
            return Disposition.FORCED;
        }
        if (forceOutcome == HookOutcome.CALLER_INTERRUPTED) {
            Thread.currentThread().interrupt();
            throw new CloseException(FailureDisposition.CALLER_INTERRUPTED);
        }
        throw new CloseException(forceOutcome == HookOutcome.TIMED_OUT
                ? FailureDisposition.FORCE_TIMED_OUT
                : FailureDisposition.FORCE_FAILED);
    }

    private static HookOutcome runBounded(String purpose, Duration timeout, Runnable operation) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            operation.run();
            return null;
        });
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("resource-gateway-browser-session-close-" + purpose + '-'
                        + CLOSER_SEQUENCE.incrementAndGet())
                .start(task);
        try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return HookOutcome.COMPLETED;
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            return HookOutcome.TIMED_OUT;
        } catch (InterruptedException callerInterrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return HookOutcome.CALLER_INTERRUPTED;
        } catch (ExecutionException | java.util.concurrent.CancellationException failed) {
            task.cancel(true);
            return HookOutcome.FAILED;
        } finally {
            if (!task.isDone()) {
                worker.interrupt();
            }
        }
    }

    private static void runBestEffortForce(Runnable forceClose) {
        Thread.ofPlatform()
                .daemon(true)
                .name("resource-gateway-browser-session-close-force-after-interruption-"
                        + CLOSER_SEQUENCE.incrementAndGet())
                .start(() -> {
                    try {
                        forceClose.run();
                    } catch (RuntimeException ignored) {
                        // Interruption remains the only closed outcome exposed to the caller.
                    }
                });
    }

    private static Duration boundedTimeout(Duration timeout) {
        Duration required = Objects.requireNonNull(timeout, "timeout");
        if (required.compareTo(MINIMUM_TIMEOUT) < 0
                || required.compareTo(MAXIMUM_TIMEOUT) > 0
                || !required.equals(Duration.ofMillis(required.toMillis()))) {
            throw new IllegalArgumentException("Browser session close timeout is invalid");
        }
        return required;
    }

    /** Successful close path. */
    enum Disposition {
        /** WebDriver protocol shutdown completed inside its deadline. */
        GRACEFUL,

        /** Independent force-close completed after graceful shutdown failed or timed out. */
        FORCED
    }

    /** Closed failure path that is safe to expose in test summaries. */
    enum FailureDisposition {
        /** The waiting test thread was interrupted and its interrupt flag was restored. */
        CALLER_INTERRUPTED,

        /** The force-close phase did not complete before its independent deadline. */
        FORCE_TIMED_OUT,

        /** The force-close hook failed. */
        FORCE_FAILED
    }

    /** Close failure that intentionally omits WebDriver and process diagnostics. */
    static final class CloseException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final FailureDisposition disposition;

        private CloseException(FailureDisposition disposition) {
            super("Browser session close was " + Objects.requireNonNull(disposition, "disposition")
                    .name().toLowerCase(Locale.ROOT));
            this.disposition = disposition;
        }

        /**
         * Returns the closed failure outcome.
         *
         * @return interruption, force timeout, or force failure
         */
        FailureDisposition disposition() {
            return disposition;
        }
    }

    private enum HookOutcome {
        COMPLETED,
        TIMED_OUT,
        CALLER_INTERRUPTED,
        FAILED
    }
}
