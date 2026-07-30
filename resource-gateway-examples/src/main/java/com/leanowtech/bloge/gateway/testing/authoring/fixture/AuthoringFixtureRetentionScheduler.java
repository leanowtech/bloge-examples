package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Objects;

/** Bounded anti-entropy worker that erases expired fixture ciphertext. */
public final class AuthoringFixtureRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            AuthoringFixtureRetentionScheduler.class);

    private final AuthoringFixtureRepository fixtures;
    private final Clock clock;
    private final int batchSize;

    public AuthoringFixtureRetentionScheduler(
            AuthoringFixtureRepository fixtures,
            Clock clock,
            int batchSize) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = Math.max(1, Math.min(1_000, batchSize));
    }

    @Scheduled(fixedDelayString =
            "${gateway.testing.authoring-fixtures.sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int expired = fixtures.expireDue(clock.instant(), batchSize);
            if (expired > 0) {
                log.info(
                        "Expired {} governed authoring fixture payloads",
                        expired);
            }
        } catch (RuntimeException failure) {
            log.error(
                    "Authoring fixture retention sweep failed; "
                            + "eligible payloads will be retried",
                    failure);
        }
    }
}
