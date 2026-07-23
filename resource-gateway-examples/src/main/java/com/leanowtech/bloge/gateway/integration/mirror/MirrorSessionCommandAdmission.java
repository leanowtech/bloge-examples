package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fair, non-blocking replica-local backpressure boundary for stateful-mirror commands.
 *
 * <p>The permit is acquired before a caller can wait on a per-session serialization lock. This
 * bounds both executing commands and queued servlet work. Rejection is immediate so the HTTP
 * adapter can return the stable retryable capacity problem instead of exhausting request threads
 * or the state-plane connection pool.</p>
 */
public final class MirrorSessionCommandAdmission {
    private static final int MAXIMUM_CONCURRENT_COMMANDS = 4_096;
    private final Semaphore permits;
    private final int maximumConcurrentCommands;
    private final MirrorSessionCapacityTelemetry telemetry;

    /**
     * Creates a fair bounded command admission gate.
     *
     * @param maximumConcurrentCommands positive local in-flight command limit
     * @param telemetry fixed-cardinality telemetry sink
     */
    public MirrorSessionCommandAdmission(
            int maximumConcurrentCommands,
            MirrorSessionCapacityTelemetry telemetry) {
        if (maximumConcurrentCommands < 1
                || maximumConcurrentCommands
                > MAXIMUM_CONCURRENT_COMMANDS) {
            throw new IllegalArgumentException(
                    "mirror concurrent-command limit is invalid");
        }
        this.maximumConcurrentCommands = maximumConcurrentCommands;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        permits = new Semaphore(maximumConcurrentCommands, true);
    }

    /**
     * Attempts one immediate admission without creating a hidden queue.
     *
     * @return an exactly releasable permit, or empty when locally saturated
     */
    public Optional<Permit> tryAcquire() {
        if (!permits.tryAcquire()) {
            telemetry.record(
                    MirrorSessionCapacityTelemetry.Boundary.REPLICA,
                    MirrorSessionCapacityTelemetry.Decision.REJECTED);
            return Optional.empty();
        }
        telemetry.record(
                MirrorSessionCapacityTelemetry.Boundary.REPLICA,
                MirrorSessionCapacityTelemetry.Decision.ADMITTED);
        telemetry.commandStarted();
        return Optional.of(new Permit(permits, telemetry));
    }

    /** @return current local in-flight command count */
    public int inflight() {
        return maximumConcurrentCommands - permits.availablePermits();
    }

    /** Exactly-once release token for one locally admitted command. */
    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final MirrorSessionCapacityTelemetry telemetry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(
                Semaphore permits,
                MirrorSessionCapacityTelemetry telemetry) {
            this.permits = permits;
            this.telemetry = telemetry;
        }

        /**
         * Returns local capacity exactly once.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                telemetry.commandFinished();
                permits.release();
            }
        }
    }
}
