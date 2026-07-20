package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic, process-safe driver for bounded external reconciliation source retirement.
 *
 * <p>The database control plane owns eligibility, cross-replica lease fencing, historical
 * signature verification, and atomic mutation. This driver adds only process-local overlap
 * exclusion, fixed-delay invocation, identity-free liveness state, and contained retry. A live
 * lease held by another replica is a normal outcome and never consumes the failure budget.</p>
 */
public final class TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class);
    private static final Duration MINIMUM_RETENTION = Duration.ofDays(1);
    private static final Duration MAXIMUM_RETENTION = Duration.ofDays(3650);

    private final DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            controlPlane;
    private final Duration processedRetention;
    private final Duration expiredRetention;
    private final int pageSize;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<TickResult> latest = new AtomicReference<>(TickResult.notRun());

    /**
     * Creates the test/staging source-history lifecycle driver.
     *
     * @param controlPlane database-clock source-retirement authority
     * @param processedRetention fully governed source-history window, one day through ten years
     * @param expiredRetention terminal unprocessed snapshot window, one day through ten years
     * @param pageSize maximum rows removed from one child segment per tick, 1 through 500
     */
    public TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
            DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    controlPlane,
            Duration processedRetention,
            Duration expiredRetention,
            int pageSize) {
        this(controlPlane, processedRetention, expiredRetention, pageSize, Clock.systemUTC());
    }

    /** Package-visible clock seam keeps liveness and recovery tests deterministic. */
    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
            DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    controlPlane,
            Duration processedRetention,
            Duration expiredRetention,
            int pageSize,
            Clock clock) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.processedRetention = bounded(processedRetention, "processedRetention");
        this.expiredRetention = bounded(expiredRetention, "expiredRetention");
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException(
                    "External source retention page must be 1 through 500");
        }
        this.pageSize = pageSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Executes one independently committed source-retirement step.
     *
     * <p>Failures are contained so the fixed-delay scheduler remains alive. The return value is
     * deliberately nullable only for a contained failure or process-local overlap; durable health
     * remains authoritative in the control-plane operational snapshot.</p>
     *
     * @return committed or lease-busy attempt, or {@code null} when this invocation was contained
     */
    @Scheduled(
            initialDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.source-retention-initial-delay-ms:300000}",
            fixedDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.source-retention-interval-ms:3600000}")
    public DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            .RetentionAttempt retain() {
        Instant attemptedAt = clock.instant();
        if (!running.compareAndSet(false, true)) {
            latest.updateAndGet(previous -> TickResult.overlap(previous, attemptedAt));
            return null;
        }
        try {
            var attempt = controlPlane.retain(
                    processedRetention, expiredRetention, pageSize);
            latest.updateAndGet(previous -> TickResult.completed(previous, attemptedAt, attempt));
            if (attempt.status()
                    == DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    .RetentionStatus.COMPLETED) {
                var result = attempt.result();
                if (result.classificationsDeleted() > 0 || result.expectedDeleted() > 0
                        || result.itemsDeleted() > 0 || result.pagesDeleted() > 0
                        || result.sourceRetired()) {
                    log.info("External source retention classificationsDeleted={}, "
                                    + "expectedDeleted={}, itemsDeleted={}, pagesDeleted={}, "
                                    + "sourceRetired={}",
                            result.classificationsDeleted(), result.expectedDeleted(),
                            result.itemsDeleted(), result.pagesDeleted(), result.sourceRetired());
                }
            }
            return attempt;
        } catch (RuntimeException unavailable) {
            latest.updateAndGet(previous -> TickResult.failed(previous, attemptedAt));
            log.warn("External source retention failed; committed progress remains quarantined "
                    + "behind its permanent marker and will be retried");
            return null;
        } finally {
            running.set(false);
        }
    }

    /** @return latest identity-free process-local invocation state */
    public TickResult latest() {
        return latest.get();
    }

    private static Duration bounded(Duration value, String name) {
        Duration exact = Objects.requireNonNull(value, name);
        if (exact.compareTo(MINIMUM_RETENTION) < 0
                || exact.compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between one day and ten years");
        }
        return exact;
    }

    /** Closed process-local outcomes; database lease contention is explicitly non-failing. */
    public enum TickStatus {
        /** No invocation has started in this process. */
        NOT_RUN,
        /** One bounded transaction, including an idle transaction, committed. */
        COMPLETED,
        /** Another replica owns the database-clock lease. */
        LEASE_BUSY,
        /** The control-plane invocation failed and was contained. */
        FAILED,
        /** A concurrent process-local invocation was rejected. */
        LOCAL_OVERLAP
    }

    /**
     * Identity-free local scheduler state.
     *
     * @param sequence monotonic process-local attempt count
     * @param status latest closed outcome
     * @param attemptedAt latest attempt time, absent only before the first attempt
     * @param lastSuccessfulAt latest local commit time; lease-busy does not replace it
     * @param consecutiveFailures failed or overlapping attempts since the latest local commit
     */
    public record TickResult(
            long sequence,
            TickStatus status,
            Instant attemptedAt,
            Instant lastSuccessfulAt,
            long consecutiveFailures) {
        /** Validates monotonic, closed scheduler observations. */
        public TickResult {
            Objects.requireNonNull(status, "status");
            if (sequence < 0 || consecutiveFailures < 0
                    || status == TickStatus.NOT_RUN
                    && (sequence != 0 || attemptedAt != null || lastSuccessfulAt != null
                    || consecutiveFailures != 0)
                    || status != TickStatus.NOT_RUN && attemptedAt == null
                    || lastSuccessfulAt != null && attemptedAt != null
                    && lastSuccessfulAt.isAfter(attemptedAt)
                    || status == TickStatus.COMPLETED
                    && (lastSuccessfulAt == null || !lastSuccessfulAt.equals(attemptedAt)
                    || consecutiveFailures != 0)
                    || status == TickStatus.LEASE_BUSY && consecutiveFailures != 0
                    || (status == TickStatus.FAILED || status == TickStatus.LOCAL_OVERLAP)
                    && consecutiveFailures == 0) {
                throw new IllegalArgumentException("Invalid external source scheduler result");
            }
        }

        private static TickResult notRun() {
            return new TickResult(0, TickStatus.NOT_RUN, null, null, 0);
        }

        private static TickResult overlap(TickResult previous, Instant attemptedAt) {
            return new TickResult(increment(previous.sequence()), TickStatus.LOCAL_OVERLAP,
                    attemptedAt, previous.lastSuccessfulAt(),
                    increment(previous.consecutiveFailures()));
        }

        private static TickResult completed(
                TickResult previous,
                Instant attemptedAt,
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetentionAttempt attempt) {
            Objects.requireNonNull(attempt, "attempt");
            if (attempt.status()
                    == DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    .RetentionStatus.LEASE_BUSY) {
                return new TickResult(increment(previous.sequence()), TickStatus.LEASE_BUSY,
                        attemptedAt, previous.lastSuccessfulAt(), 0);
            }
            return new TickResult(increment(previous.sequence()), TickStatus.COMPLETED,
                    attemptedAt, attemptedAt, 0);
        }

        private static TickResult failed(TickResult previous, Instant attemptedAt) {
            return new TickResult(increment(previous.sequence()), TickStatus.FAILED,
                    attemptedAt, previous.lastSuccessfulAt(),
                    increment(previous.consecutiveFailures()));
        }

        private static long increment(long value) {
            try {
                return Math.incrementExact(value);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "External source scheduler counter overflow", overflow);
            }
        }
    }
}
