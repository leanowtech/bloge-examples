package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic bounded control loop for durable BLOGE scheduling-projection anti-entropy.
 *
 * <p>The in-memory keyset cursor is advanced only after a complete two-table sweep call. Database
 * failure therefore retries the same page. Multiple replicas may inspect or idempotently repair the
 * same page; authority-snapshot compare-and-set prevents a stale replica from overwriting fresh
 * lifecycle state. Logs contain aggregate counts only.</p>
 */
public final class DurableStateProjectionReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            DurableStateProjectionReconciliationScheduler.class);

    private final DurableStateProjectionReconciler reconciler;
    private final int pageSizePerEntity;
    private final DurableStateProjectionReconciler.RepairMode repairMode;
    private final AtomicReference<DurableStateProjectionReconciler.ScanCursor> cursor =
            new AtomicReference<>(DurableStateProjectionReconciler.ScanCursor.start());

    /**
     * Creates the profile-gated anti-entropy scheduler.
     *
     * @param reconciler system-level committed-state scanner
     * @param pageSizePerEntity rows inspected from each authority table per tick, normalized 1..1000
     * @param repairMode safe derived repair or audit-only operation
     */
    public DurableStateProjectionReconciliationScheduler(
            DurableStateProjectionReconciler reconciler,
            int pageSizePerEntity,
            DurableStateProjectionReconciler.RepairMode repairMode) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.pageSizePerEntity = Math.max(1, Math.min(pageSizePerEntity, 1000));
        this.repairMode = Objects.requireNonNull(repairMode, "repairMode");
    }

    /** Performs one sweep; storage failure retains the prior cursor for a later retry. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.projection-reconciliation-interval-ms:60000}")
    public void reconcile() {
        DurableStateProjectionReconciler.ScanCursor current = cursor.get();
        try {
            DurableStateProjectionReconciler.SweepResult result =
                    reconciler.sweep(current, pageSizePerEntity, repairMode);
            cursor.set(result.nextCursor());
            if (!result.findings().isEmpty()) {
                log.warn("Durable-state projection reconciliation scanned={}, drifted={}, "
                                + "unreadable={}, repaired={}, raced={}",
                        result.scanned(), result.drifted(), result.unreadable(),
                        result.repaired(), result.raced());
            }
        } catch (RuntimeException unavailable) {
            log.warn("Durable-state projection reconciliation failed; "
                    + "the same cursor will be retried on the next scheduled sweep");
        }
    }
}
