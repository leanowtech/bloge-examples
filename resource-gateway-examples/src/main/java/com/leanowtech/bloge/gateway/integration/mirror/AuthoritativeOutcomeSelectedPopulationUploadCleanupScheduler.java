package com.leanowtech.bloge.gateway.integration.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * Periodically expires abandoned selected-population uploads and purges old terminal records.
 *
 * <p>Each turn is bounded and payload-free in logs. A failed turn leaves all durable state
 * retryable by the next schedule, including when multiple Resource Gateway replicas run the
 * same lifecycle controller.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler
                    .class);

    private final AuthoritativeOutcomeSelectedPopulationUploadService
            service;
    private final int batchSize;

    /**
     * Creates one bounded cleanup controller.
     *
     * @param service durable upload lifecycle boundary
     * @param batchSize maximum upload intents considered per turn, from 1 through 10,000
     */
    public AuthoritativeOutcomeSelectedPopulationUploadCleanupScheduler(
            AuthoritativeOutcomeSelectedPopulationUploadService
                    service,
            int batchSize) {
        this.service = Objects.requireNonNull(
                service, "service");
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException(
                    "upload cleanup batchSize must be between 1 and 10000");
        }
        this.batchSize = batchSize;
    }

    /**
     * Runs one bounded expiry and retention page.
     *
     * <p>Failures are deliberately contained because the next scheduled turn is the recovery
     * mechanism; no payload or tenant coordinate is emitted to logs.</p>
     */
    @Scheduled(fixedDelayString =
            "${gateway.testing.mirror.selected-population.upload-cleanup-interval-ms:60000}")
    public void sweep() {
        try {
            int expired = service.cleanup(batchSize);
            if (expired > 0) {
                log.info(
                        "Expired {} abandoned selected-population uploads",
                        expired);
            }
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Selected-population upload cleanup failed; durable state remains retryable");
        }
    }
}
