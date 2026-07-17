package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodically bounds detailed recovery-sequence replay state and its derived commands.
 *
 * <p>The repository elects one replica with a database-clock lease. Each page atomically replaces
 * detailed outer requests with keyed tombstones, verifies and erases derived steps, claims and
 * automatic heartbeats, purges expired tombstones, advances aggregate counters, and releases the
 * lease. Scheduler logs never include request, tenant, run, payload, or credential material.</p>
 */
public final class DurableRecoverySequenceRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            DurableRecoverySequenceRetentionScheduler.class);
    private static final Duration MIN_COMMAND_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_TOMBSTONE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3_650);
    private static final Duration MIN_SCHEDULE_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAX_SCHEDULE_INTERVAL = Duration.ofDays(30);

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final Duration commandRetention;
    private final Duration tombstoneRetention;
    private final int pageSize;
    private final DurableRecoverySequenceRetentionTelemetry telemetry;

    /**
     * Creates a profile-gated lifecycle loop with eager policy validation.
     *
     * @param checkpoints durable sequence, tombstone, lease, and counter authority
     * @param commandRetention exact detailed replay window
     * @param tombstoneRetention request-key reservation after detail erasure
     * @param pageSize per-category bound from 1 through 1,000
     */
    public DurableRecoverySequenceRetentionScheduler(
            DurableTestExecutionCheckpointRepository checkpoints,
            Duration commandRetention,
            Duration tombstoneRetention,
            int pageSize) {
        this(checkpoints, commandRetention, tombstoneRetention, pageSize,
                DurableRecoverySequenceRetentionTelemetry.noop(), Duration.ofHours(1));
    }

    /**
     * Creates the lifecycle loop with fixed-cardinality operational telemetry.
     *
     * @param checkpoints durable sequence, tombstone, lease, and counter authority
     * @param commandRetention exact detailed replay window
     * @param tombstoneRetention request-key reservation after detail erasure
     * @param pageSize per-category bound from 1 through 1,000
     * @param telemetry aggregate-only metrics adapter
     * @param scheduleInterval configured fixed delay validated against busy loops
     */
    public DurableRecoverySequenceRetentionScheduler(
            DurableTestExecutionCheckpointRepository checkpoints,
            Duration commandRetention,
            Duration tombstoneRetention,
            int pageSize,
            DurableRecoverySequenceRetentionTelemetry telemetry,
            Duration scheduleInterval) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.commandRetention = bounded(
                commandRetention, MIN_COMMAND_RETENTION, "commandRetention");
        this.tombstoneRetention = bounded(
                tombstoneRetention, MIN_TOMBSTONE_RETENTION, "tombstoneRetention");
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
        this.pageSize = pageSize;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        boundedScheduleInterval(scheduleInterval);
    }

    /** Runs one leased page; a failed transaction remains retryable from its prior checkpoint. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.recovery-sequences.retention-interval-ms:3600000}")
    public void retain() {
        long startedAt = System.nanoTime();
        DurableTestExecutionCheckpointRepository.RecoverySequenceRetentionAttempt attempt;
        try {
            attempt = checkpoints.retainRecoverySequencePage(
                    commandRetention, tombstoneRetention, pageSize);
        } catch (RuntimeException unavailable) {
            recordFailure(elapsed(startedAt));
            log.warn("Recovery-sequence retention transaction failed; sequence commands, "
                    + "derived children, tombstones, and counters remain at the last "
                    + "committed page");
            return;
        }

        record(attempt, elapsed(startedAt));
        if (attempt.status()
                == DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionStatus.LEASE_BUSY) {
            return;
        }
        try {
            telemetry.refresh(checkpoints.recoverySequenceRetentionSnapshot());
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Recovery-sequence retention telemetry refresh failed after the page "
                    + "was committed");
        }
        var result = attempt.result();
        if (result.sequencesTombstoned() > 0 || result.tombstonesPurged() > 0) {
            log.info("Recovery-sequence retention tombstoned={}, stepsPurged={}, "
                            + "claimsPurged={}, heartbeatsPurged={}, tombstonesPurged={}",
                    result.sequencesTombstoned(), result.recoveryStepsPurged(),
                    result.ownerClaimsPurged(), result.heartbeatsPurged(),
                    result.tombstonesPurged());
        }
    }

    private void record(
            DurableTestExecutionCheckpointRepository.RecoverySequenceRetentionAttempt attempt,
            Duration elapsed) {
        try {
            telemetry.record(attempt, elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Recovery-sequence retention attempt telemetry could not be recorded");
        }
    }

    private void recordFailure(Duration elapsed) {
        try {
            telemetry.recordFailure(elapsed);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Recovery-sequence retention failure telemetry could not be recorded");
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
