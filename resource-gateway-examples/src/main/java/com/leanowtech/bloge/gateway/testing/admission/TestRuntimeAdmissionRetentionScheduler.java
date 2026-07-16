package com.leanowtech.bloge.gateway.testing.admission;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Bounded best-effort cleanup of expired admission leases after crash-based recovery. */
public final class TestRuntimeAdmissionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            TestRuntimeAdmissionRetentionScheduler.class);

    private final DatabaseTestRuntimeAdmissionControl controlPlane;
    private final int batchSize;

    /**
     * Creates a profile-gated oldest-first cleanup loop.
     *
     * @param controlPlane admission lease authority
     * @param batchSize positive bounded cleanup page
     */
    public TestRuntimeAdmissionRetentionScheduler(
            DatabaseTestRuntimeAdmissionControl controlPlane,
            int batchSize) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        if (batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "Admission retention batchSize must be between 1 and 10000");
        }
        this.batchSize = batchSize;
    }

    /** Removes at most one configured page and never leaks persistence details into logs. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.admission.cleanup-interval-ms:60000}")
    public void sweep() {
        try {
            controlPlane.purgeExpired(batchSize);
        } catch (RuntimeException unavailable) {
            log.warn("Test-runtime admission retention sweep failed; expired permits remain non-live");
        }
    }
}
