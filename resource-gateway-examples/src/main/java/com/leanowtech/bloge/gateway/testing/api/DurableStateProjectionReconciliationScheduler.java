package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodic bounded control loop for durable BLOGE scheduling-projection anti-entropy.
 *
 * <p>The database control plane chooses one replica and atomically commits projection repair,
 * payload-free finding lifecycle, and both keyset cursors. A storage failure leaves the durable
 * cursor unchanged. Logs contain aggregate counts only.</p>
 */
public final class DurableStateProjectionReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            DurableStateProjectionReconciliationScheduler.class);

    private final DatabaseDurableStateProjectionControlPlane controlPlane;
    private final int pageSizePerEntity;
    private final DurableStateProjectionReconciler.RepairMode repairMode;
    private final DurableStateProjectionTelemetry telemetry;

    /**
     * Creates the profile-gated anti-entropy scheduler.
     *
     * @param controlPlane database-leased scanner, finding queue, and cursor authority
     * @param pageSizePerEntity rows inspected from each authority table per tick, normalized 1..1000
     * @param repairMode safe derived repair or audit-only operation
     */
    public DurableStateProjectionReconciliationScheduler(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            int pageSizePerEntity,
            DurableStateProjectionReconciler.RepairMode repairMode) {
        this(controlPlane, pageSizePerEntity, repairMode,
                DurableStateProjectionTelemetry.noop());
    }

    /**
     * Creates the scheduler with a bounded-cardinality metrics adapter.
     *
     * @param controlPlane database-leased scanner, finding queue, and cursor authority
     * @param pageSizePerEntity rows inspected from each authority table per tick
     * @param repairMode safe derived repair or audit-only operation
     * @param telemetry aggregate attempt and duration recorder
     */
    public DurableStateProjectionReconciliationScheduler(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            int pageSizePerEntity,
            DurableStateProjectionReconciler.RepairMode repairMode,
            DurableStateProjectionTelemetry telemetry) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.pageSizePerEntity = Math.max(1, Math.min(pageSizePerEntity, 1000));
        this.repairMode = Objects.requireNonNull(repairMode, "repairMode");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Performs one leased sweep; storage failure retains the durable cursor for a later retry. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.projection-reconciliation-interval-ms:60000}")
    public void reconcile() {
        long startedAt = System.nanoTime();
        try {
            DatabaseDurableStateProjectionControlPlane.SweepAttempt attempt =
                    controlPlane.reconcilePage(pageSizePerEntity, repairMode);
            telemetry.recordReconciliation(attempt, elapsed(startedAt));
            if (attempt.status()
                    == DatabaseDurableStateProjectionControlPlane.SweepStatus.LEASE_BUSY) {
                return;
            }
            DurableStateProjectionReconciler.SweepResult result = attempt.result();
            if (!result.findings().isEmpty()) {
                log.warn("Durable-state projection reconciliation scanned={}, drifted={}, "
                                + "unreadable={}, repaired={}, raced={}",
                        result.scanned(), result.drifted(), result.unreadable(),
                        result.repaired(), result.raced());
            }
        } catch (RuntimeException unavailable) {
            telemetry.recordReconciliationFailure(elapsed(startedAt));
            log.warn("Durable-state projection reconciliation failed; "
                    + "the durable cursor remains at the previous committed page");
        }
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }
}
