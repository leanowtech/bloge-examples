package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodically bounds detailed suite-stability job records and request tombstones.
 *
 * <p>The repository elects one replica with a database-clock lease and commits each bounded page
 * atomically. Scheduler logs and telemetry contain only aggregate counts and closed outcomes; they
 * never include tenant, job, request, suite, actor, key, or payload material.</p>
 */
public final class TestSuiteStabilityJobRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityJobRetentionScheduler.class);
    private static final Duration MINIMUM_TOMBSTONE_RETENTION = Duration.ofDays(1);
    private static final Duration MAXIMUM_TOMBSTONE_RETENTION = Duration.ofDays(3650);
    private static final Duration MINIMUM_SCHEDULE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_SCHEDULE_INTERVAL = Duration.ofDays(30);

    private final TestSuiteStabilityJobRepository repository;
    private final Duration tombstoneRetention;
    private final int pageSize;
    private final TestSuiteStabilityJobRetentionTelemetry telemetry;

    /**
     * Creates a retention loop with eager bounds and a no-op metric adapter.
     *
     * @param repository database lease, tombstone, counter, and snapshot authority
     * @param tombstoneRetention request reservation after detailed job erasure
     * @param pageSize independent source and tombstone page bound from 1 through 1,000
     */
    public TestSuiteStabilityJobRetentionScheduler(
            TestSuiteStabilityJobRepository repository,
            Duration tombstoneRetention,
            int pageSize) {
        this(repository, tombstoneRetention, pageSize,
                TestSuiteStabilityJobRetentionTelemetry.noop(), Duration.ofHours(1));
    }

    /**
     * Creates the lifecycle loop with fixed-cardinality operational telemetry.
     *
     * @param repository database lease, tombstone, counter, and snapshot authority
     * @param tombstoneRetention request reservation after detailed job erasure
     * @param pageSize independent source and tombstone page bound from 1 through 1,000
     * @param telemetry aggregate-only metric adapter
     * @param scheduleInterval configured fixed delay validated against busy loops
     */
    public TestSuiteStabilityJobRetentionScheduler(
            TestSuiteStabilityJobRepository repository,
            Duration tombstoneRetention,
            int pageSize,
            TestSuiteStabilityJobRetentionTelemetry telemetry,
            Duration scheduleInterval) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tombstoneRetention = boundedTombstoneRetention(tombstoneRetention);
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
        this.pageSize = pageSize;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        boundedScheduleInterval(scheduleInterval);
    }

    /** Runs one leased page; failure leaves the prior committed page fully authoritative. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.stability-jobs.retention.interval-ms:3600000}")
    public void retain() {
        long startedAt = System.nanoTime();
        TestSuiteStabilityJobRetentionAttempt attempt;
        try {
            attempt = repository.retainExpired(tombstoneRetention, pageSize);
        } catch (RuntimeException unavailable) {
            recordFailure(elapsed(startedAt));
            log.warn("Suite-stability job retention transaction failed; detailed jobs, "
                    + "tombstones, and counters remain at the last committed page");
            return;
        }

        record(attempt, elapsed(startedAt));
        if (attempt.status() == TestSuiteStabilityJobRetentionAttempt.Status.LEASE_BUSY) {
            return;
        }
        try {
            telemetry.refresh(repository.observeRetention());
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability job retention telemetry refresh failed after the page "
                    + "was committed");
        }
        TestSuiteStabilityJobRetentionResult result = attempt.result();
        if (result.jobsTombstoned() > 0 || result.tombstonesPurged() > 0) {
            log.info("Suite-stability job retention tombstoned={}, tombstonesPurged={}",
                    result.jobsTombstoned(), result.tombstonesPurged());
        }
    }

    private void record(TestSuiteStabilityJobRetentionAttempt attempt, Duration elapsed) {
        try {
            telemetry.record(attempt, elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability job retention attempt telemetry could not be recorded");
        }
    }

    private void recordFailure(Duration elapsed) {
        try {
            telemetry.recordFailure(elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability job retention failure telemetry could not be recorded");
        }
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static Duration boundedTombstoneRetention(Duration value) {
        Duration safe = Objects.requireNonNull(value, "tombstoneRetention");
        if (safe.getNano() != 0
                || safe.compareTo(MINIMUM_TOMBSTONE_RETENTION) < 0
                || safe.compareTo(MAXIMUM_TOMBSTONE_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "tombstoneRetention must be whole seconds between 1 and 3650 days");
        }
        return safe;
    }

    private static Duration boundedScheduleInterval(Duration value) {
        Duration safe = Objects.requireNonNull(value, "scheduleInterval");
        if (safe.compareTo(MINIMUM_SCHEDULE_INTERVAL) < 0
                || safe.compareTo(MAXIMUM_SCHEDULE_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "scheduleInterval must be between PT1S and PT720H");
        }
        return safe;
    }
}
