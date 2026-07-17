package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodically bounds worker-quarantine command, approval, tombstone, and history storage.
 *
 * <p>The database authority elects one replica with a database-clock lease and commits every
 * bounded page atomically. Detailed replay rows become payload-free idempotency tombstones before
 * deletion; history and expired tombstones are removed only after their independent windows.
 * Scheduler logs contain aggregate counts and never request IDs, claim tokens, or business data.</p>
 */
public final class DurableWorkerQuarantineRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            DurableWorkerQuarantineRetentionScheduler.class);
    private static final Duration MIN_COMMAND_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_HISTORY_RETENTION = Duration.ofDays(1);
    private static final Duration MIN_TOMBSTONE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3_650);
    private static final Duration MIN_SCHEDULE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAX_SCHEDULE_INTERVAL = Duration.ofDays(30);

    private final DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private final Duration commandRetention;
    private final Duration historyRetention;
    private final Duration tombstoneRetention;
    private final int pageSize;
    private final DurableWorkerQuarantineRetentionTelemetry telemetry;

    /**
     * Creates the profile-gated retention loop and validates every lifecycle window eagerly.
     *
     * @param controlPlane durable lease, tombstone, purge, and counter authority
     * @param commandRetention exact replay retention after a command or approval deadline
     * @param historyRetention token-free history retention
     * @param tombstoneRetention request-ID reservation after detailed replay removal
     * @param pageSize independent per-category processing bound from 1 through 1,000
     */
    public DurableWorkerQuarantineRetentionScheduler(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            Duration commandRetention,
            Duration historyRetention,
            Duration tombstoneRetention,
            int pageSize) {
        this(controlPlane, commandRetention, historyRetention, tombstoneRetention, pageSize,
                DurableWorkerQuarantineRetentionTelemetry.noop(), Duration.ofHours(1));
    }

    /**
     * Creates the retention loop with fixed-cardinality operational telemetry.
     *
     * @param controlPlane durable lease, tombstone, purge, and counter authority
     * @param commandRetention exact replay retention after a command or approval deadline
     * @param historyRetention token-free history retention
     * @param tombstoneRetention request-ID reservation after detailed replay removal
     * @param pageSize independent per-category processing bound from 1 through 1,000
     * @param telemetry aggregate-only attempt, duration, and lifecycle gauges
     * @param scheduleInterval configured fixed delay, validated to prevent a busy loop
     */
    public DurableWorkerQuarantineRetentionScheduler(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            Duration commandRetention,
            Duration historyRetention,
            Duration tombstoneRetention,
            int pageSize,
            DurableWorkerQuarantineRetentionTelemetry telemetry,
            Duration scheduleInterval) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.commandRetention = bounded(
                commandRetention, MIN_COMMAND_RETENTION, "commandRetention");
        this.historyRetention = bounded(
                historyRetention, MIN_HISTORY_RETENTION, "historyRetention");
        this.tombstoneRetention = bounded(
                tombstoneRetention, MIN_TOMBSTONE_RETENTION, "tombstoneRetention");
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
        this.pageSize = pageSize;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        boundedScheduleInterval(scheduleInterval);
    }

    /** Runs one leased page; failures remain fully retryable at the previous checkpoint. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.worker-quarantines.retention-interval-ms:3600000}")
    public void retain() {
        long startedAt = System.nanoTime();
        DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt attempt;
        try {
            attempt = controlPlane.retainPage(commandRetention, historyRetention,
                    tombstoneRetention, pageSize);
        } catch (RuntimeException unavailable) {
            recordFailure(elapsed(startedAt));
            log.warn("Worker quarantine retention transaction failed; source rows, "
                    + "tombstones, history, and counters remain at the last committed page");
            return;
        }

        record(attempt, elapsed(startedAt));
        if (attempt.status()
                == DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.LEASE_BUSY) {
            return;
        }
        try {
            telemetry.refresh(controlPlane.retentionSnapshot());
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Worker quarantine retention telemetry refresh failed after the page "
                    + "was committed");
        }
        DatabaseDurableWorkerQuarantineControlPlane.RetentionResult result = attempt.result();
        if (result.tombstoned() > 0 || result.tombstonesPurged() > 0
                || result.historiesPurged() > 0) {
            log.info("Worker quarantine retention tombstoned={}, tombstonesPurged={}, "
                            + "historiesPurged={}", result.tombstoned(),
                    result.tombstonesPurged(), result.historiesPurged());
        }
    }

    private void record(
            DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt attempt,
            Duration elapsed) {
        try {
            telemetry.record(attempt, elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Worker quarantine retention attempt telemetry could not be recorded");
        }
    }

    private void recordFailure(Duration elapsed) {
        try {
            telemetry.recordFailure(elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Worker quarantine retention failure telemetry could not be recorded");
        }
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static Duration bounded(Duration value, Duration minimum, String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.compareTo(minimum) < 0 || safe.compareTo(MAX_RETENTION) > 0
                || safe.getNano() != 0) {
            throw new IllegalArgumentException(name + " must be whole seconds between "
                    + minimum + " and " + MAX_RETENTION);
        }
        return safe;
    }

    private static Duration boundedScheduleInterval(Duration value) {
        Duration safe = Objects.requireNonNull(value, "scheduleInterval");
        if (safe.compareTo(MIN_SCHEDULE_INTERVAL) < 0
                || safe.compareTo(MAX_SCHEDULE_INTERVAL) > 0) {
            throw new IllegalArgumentException("scheduleInterval must be between "
                    + MIN_SCHEDULE_INTERVAL + " and " + MAX_SCHEDULE_INTERVAL);
        }
        return safe;
    }
}
