package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded non-overlapping worker for lease-expired Session write intents.
 *
 * <p>The database store owns cross-replica locking and receipt-based outcome derivation. This
 * worker contributes only a replica-local overlap guard and fixed-cardinality telemetry. It never
 * reads a command payload, logs a customer coordinate, or guesses an outcome after a store
 * failure.</p>
 */
public final class MirrorSessionWriteAttemptReconciliationScheduler {
    private static final long MINIMUM_SWEEP_INTERVAL_MILLIS = 1_000;
    private static final long MAXIMUM_SWEEP_INTERVAL_MILLIS =
            3_600_000;
    private final MirrorSessionStateStore store;
    private final MirrorSessionCapacityTelemetry telemetry;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates the bounded reconciliation worker.
     *
     * @param store dedicated encrypted state store and attempt journal
     * @param telemetry fixed-cardinality Session telemetry
     * @param batchSize positive page size capped by the store contract
     * @param sweepIntervalMillis bounded delay between sweeps
     */
    public MirrorSessionWriteAttemptReconciliationScheduler(
            MirrorSessionStateStore store,
            MirrorSessionCapacityTelemetry telemetry,
            int batchSize,
            long sweepIntervalMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.telemetry = Objects.requireNonNull(
                telemetry, "telemetry");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException(
                    "write-attempt reconciliation batch size must be between 1 and 1000");
        }
        if (sweepIntervalMillis
                < MINIMUM_SWEEP_INTERVAL_MILLIS
                || sweepIntervalMillis
                > MAXIMUM_SWEEP_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    "write-attempt reconciliation interval must be between 1000 and 3600000 milliseconds");
        }
        this.batchSize = batchSize;
    }

    /** Runs one bounded page without overlapping another local tick. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.mirror.stateful.write-attempt-reconciliation.sweep-interval-millis:5000}")
    public void sweep() {
        if (!running.compareAndSet(false, true)) {
            telemetry.writeAttemptReconciliationSkipped();
            return;
        }
        try {
            telemetry.writeAttemptReconciliationCompleted(
                    store.reconcileWriteAttempts(batchSize));
        } catch (RuntimeException unavailable) {
            telemetry.writeAttemptReconciliationFailed();
        } finally {
            running.set(false);
        }
    }
}
