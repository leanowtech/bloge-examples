package com.leanowtech.bloge.gateway.visual.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Bounded background enforcement of payload expiry; reads also enforce expiry synchronously. */
@Component
public final class VisualRunPayloadRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(VisualRunPayloadRetentionScheduler.class);

    private final VisualRunPayloadRepository repository;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public VisualRunPayloadRetentionScheduler(
            VisualRunPayloadRepository repository,
            @Value("${gateway.integration.payload-governance.sweep-batch-size:200}") int batchSize) {
        this(repository, batchSize, Clock.systemUTC());
    }

    VisualRunPayloadRetentionScheduler(VisualRunPayloadRepository repository, int batchSize, Clock clock) {
        this.repository = repository;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Scheduled(fixedDelayString = "${gateway.integration.payload-governance.sweep-interval-ms:60000}")
    public void purgeExpired() {
        int purged = repository.purgeExpired(clock.instant(), batchSize);
        if (purged > 0) {
            log.info("Purged {} expired governed run payloads", purged);
        }
    }
}
