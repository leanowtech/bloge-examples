package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded anti-entropy sweep that removes expired replay values while retaining tombstones. */
public final class ReplayPayloadRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplayPayloadRetentionScheduler.class);

    private final ReplayPayloadRepository repository;
    private final int batchSize;

    /**
     * Creates a bounded scheduled retention worker.
     *
     * @param repository isolated replay payload vault
     * @param batchSize maximum values removed per sweep
     */
    public ReplayPayloadRetentionScheduler(ReplayPayloadRepository repository, int batchSize) {
        this.repository = repository;
        this.batchSize = Math.max(1, Math.min(1_000, batchSize));
    }

    /** Removes one bounded batch; failures are retried by the next scheduled anti-entropy pass. */
    @Scheduled(fixedDelayString = "${gateway.testing.replay-payloads.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int expired = repository.purgeExpired(batchSize);
            if (expired > 0) {
                log.info("Expired {} governed test replay payload values", expired);
            }
        } catch (RuntimeException failure) {
            log.error("Replay payload retention sweep failed; tombstoned values will be retried", failure);
        }
    }
}
