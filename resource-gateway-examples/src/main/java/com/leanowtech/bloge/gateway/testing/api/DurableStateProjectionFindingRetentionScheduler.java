package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodic bounded lifecycle controller for resolved projection findings and their archive.
 *
 * <p>The database control plane elects one replica with a database-clock lease. A successful tick
 * atomically copies eligible token-free findings to the archive, deletes the exact source rows,
 * purges one independently bounded archive page, and advances cumulative counters. Logs contain
 * aggregate counts only.</p>
 */
public final class DurableStateProjectionFindingRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            DurableStateProjectionFindingRetentionScheduler.class);
    private static final Duration MIN_RESOLVED_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_ARCHIVE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);

    private final DatabaseDurableStateProjectionControlPlane controlPlane;
    private final Duration resolvedRetention;
    private final Duration archiveRetention;
    private final int pageSize;
    private final DurableStateProjectionTelemetry telemetry;

    /**
     * Creates the profile-gated retention loop and fails fast on unsafe lifecycle policy.
     *
     * @param controlPlane durable retention lease, archive, and counter authority
     * @param resolvedRetention active-queue retention from one hour through ten years
     * @param archiveRetention archive retention from one day through ten years
     * @param pageSize source archive and archive purge bounds, normalized to 1..1000 each
     */
    public DurableStateProjectionFindingRetentionScheduler(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            Duration resolvedRetention,
            Duration archiveRetention,
            int pageSize) {
        this(controlPlane, resolvedRetention, archiveRetention, pageSize,
                DurableStateProjectionTelemetry.noop());
    }

    /**
     * Creates the retention loop with a bounded-cardinality metrics adapter.
     *
     * @param controlPlane durable retention lease, archive, and counter authority
     * @param resolvedRetention active-queue retention from one hour through ten years
     * @param archiveRetention archive retention from one day through ten years
     * @param pageSize source archive and archive purge bounds
     * @param telemetry aggregate attempt and duration recorder
     */
    public DurableStateProjectionFindingRetentionScheduler(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            Duration resolvedRetention,
            Duration archiveRetention,
            int pageSize,
            DurableStateProjectionTelemetry telemetry) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.resolvedRetention = bounded(
                resolvedRetention, MIN_RESOLVED_RETENTION, "resolvedRetention");
        this.archiveRetention = bounded(
                archiveRetention, MIN_ARCHIVE_RETENTION, "archiveRetention");
        this.pageSize = Math.max(1, Math.min(pageSize, 1000));
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Performs one leased retention page; a failed page remains fully retryable. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.projection-findings.retention-interval-ms:3600000}")
    public void retain() {
        long startedAt = System.nanoTime();
        try {
            DatabaseDurableStateProjectionControlPlane.RetentionAttempt attempt =
                    controlPlane.retainFindings(
                            resolvedRetention, archiveRetention, pageSize);
            telemetry.recordRetention(attempt, elapsed(startedAt));
            if (attempt.status()
                    == DatabaseDurableStateProjectionControlPlane.RetentionStatus.LEASE_BUSY) {
                return;
            }
            DatabaseDurableStateProjectionControlPlane.RetentionResult result = attempt.result();
            if (result.archived() > 0 || result.purged() > 0) {
                log.info("Durable-state projection finding retention archived={}, purged={}",
                        result.archived(), result.purged());
            }
        } catch (RuntimeException unavailable) {
            telemetry.recordRetentionFailure(elapsed(startedAt));
            log.warn("Durable-state projection finding retention failed; "
                    + "the active queue and archive counters remain at the last committed page");
        }
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }

    private static Duration bounded(Duration value, Duration minimum, String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.compareTo(minimum) < 0 || safe.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum
                    + " and " + MAX_RETENTION);
        }
        return safe;
    }
}
