package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Periodic anti-entropy sweep for expired suite-run ownership leases. */
public final class TestSuiteRunReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TestSuiteRunReconciliationScheduler.class);

    private final TestSuiteRunReconciliationService service;
    private final int batchSize;

    /**
     * Creates a bounded scheduler adapter.
     *
     * @param service fail-closed reconciliation service
     * @param batchSize maximum candidates inspected per sweep
     */
    public TestSuiteRunReconciliationScheduler(TestSuiteRunReconciliationService service, int batchSize) {
        this.service = Objects.requireNonNull(service, "service");
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
    }

    /** Performs one sweep; failures are retried naturally on the next fixed-delay tick. */
    @Scheduled(fixedDelayString = "${gateway.testing.suite-runs.reconciliation-interval-ms:15000}")
    public void reconcileExpired() {
        try {
            TestSuiteRunReconciliationResult result = service.reconcileExpired(batchSize);
            if (result.scanned() > 0) {
                log.info("Suite-run reconciliation scanned={}, reconciled={}, raced={}, failed={}",
                        result.scanned(), result.reconciled(), result.raced(), result.failed());
            }
        } catch (RuntimeException unavailable) {
            log.warn("Suite-run reconciliation sweep failed; the next scheduled sweep will retry");
        }
    }
}
