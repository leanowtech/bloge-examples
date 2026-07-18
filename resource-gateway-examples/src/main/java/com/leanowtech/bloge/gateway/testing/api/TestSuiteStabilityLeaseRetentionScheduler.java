package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Periodically removes a bounded page of expired orphan suite-stability execution leases. */
public final class TestSuiteStabilityLeaseRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityLeaseRetentionScheduler.class);
    private final TestSuiteStabilityRunRepository repository;
    private final int batchSize;

    /**
     * @param repository database-authoritative lease store
     * @param batchSize maximum oldest-first rows deleted per sweep
     */
    public TestSuiteStabilityLeaseRetentionScheduler(
            TestSuiteStabilityRunRepository repository,
            int batchSize) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.batchSize = Math.max(1, Math.min(10_000, batchSize));
    }

    /** Deletes one bounded page; a later fixed-delay tick retries any store failure. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.stability-runs.lease-cleanup-interval-ms:15000}")
    public void purgeExpired() {
        try {
            int removed = repository.purgeExpiredLeases(batchSize);
            if (removed > 0) {
                log.info("Purged {} expired suite-stability execution leases", removed);
            }
        } catch (RuntimeException unavailable) {
            log.warn("Suite-stability lease cleanup failed; the next sweep will retry");
        }
    }
}
