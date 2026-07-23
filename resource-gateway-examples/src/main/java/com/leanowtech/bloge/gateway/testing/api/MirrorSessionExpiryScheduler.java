package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded non-overlapping worker that materializes due session expiry and ciphertext erasure.
 *
 * <p>The database store owns cross-replica serialization. This worker adds a replica-local overlap
 * guard and never retains failure diagnostics or customer identity. Runtime failures are converted
 * to fixed-cardinality telemetry so one failed tick cannot terminate future scheduling.</p>
 */
public final class MirrorSessionExpiryScheduler {
    private static final long MINIMUM_SWEEP_INTERVAL_MILLIS = 1_000;
    private static final long MAXIMUM_SWEEP_INTERVAL_MILLIS = 3_600_000;
    private final MirrorSessionStateStore store;
    private final MirrorSessionCapacityTelemetry telemetry;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates the bounded expiry worker.
     *
     * @param store dedicated encrypted state store
     * @param telemetry fixed-cardinality telemetry sink
     * @param batchSize positive page size capped by the store contract
     * @param sweepIntervalMillis bounded configured delay between sweeps
     */
    public MirrorSessionExpiryScheduler(
            MirrorSessionStateStore store,
            MirrorSessionCapacityTelemetry telemetry,
            int batchSize,
            long sweepIntervalMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException(
                    "mirror expiry batch size must be between 1 and 1000");
        }
        if (sweepIntervalMillis < MINIMUM_SWEEP_INTERVAL_MILLIS
                || sweepIntervalMillis
                > MAXIMUM_SWEEP_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    "mirror expiry sweep interval must be between 1000 and 3600000 milliseconds");
        }
        this.batchSize = batchSize;
    }

    /**
     * Runs one bounded page without overlapping another local tick.
     */
    @Scheduled(fixedDelayString =
            "${gateway.testing.mirror.stateful.expiry.sweep-interval-millis:30000}")
    public void sweep() {
        if (!running.compareAndSet(false, true)) {
            telemetry.expirySweepSkipped();
            return;
        }
        try {
            telemetry.expirySweepCompleted(
                    store.expireDue(batchSize));
        } catch (RuntimeException unavailable) {
            telemetry.expirySweepFailed();
        } finally {
            running.set(false);
        }
    }
}
