package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed freshness and backlog monitor for suite-stability job retention.
 *
 * <p>Every age comes from one database-clock snapshot. Health details contain only stable
 * violation codes, aggregate counts, and durations; tenant, job, request, key, suite, actor,
 * payload, and storage exception material never cross this boundary.</p>
 */
public final class TestSuiteStabilityJobRetentionSloMonitor implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityJobRetentionSloMonitor.class);

    private final TestSuiteStabilityJobRepository repository;
    private final TestSuiteStabilityJobRetentionTelemetry telemetry;
    private final Policy policy;
    private final AtomicReference<Instant> firstObservedAt = new AtomicReference<>();
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates an aggregate retention readiness monitor.
     *
     * @param repository database-clock lifecycle authority
     * @param telemetry fixed-cardinality metric adapter
     * @param policy freshness and backlog limits
     */
    public TestSuiteStabilityJobRetentionSloMonitor(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobRetentionTelemetry telemetry,
            Policy policy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Refreshes health from one integrity-verified aggregate database snapshot. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.stability-jobs.retention.slo.observation-interval-ms:30000}")
    public void refresh() {
        TestSuiteStabilityJobRetentionSnapshot snapshot;
        try {
            snapshot = repository.observeRetention();
        } catch (RuntimeException unavailable) {
            latest.set(Assessment.storeUnavailable());
            observeStoreUnavailable();
            log.warn("Suite-stability job retention SLO observation failed; health is "
                    + "fail-closed until an aggregate database snapshot succeeds");
            return;
        }

        firstObservedAt.compareAndSet(null, snapshot.observedAt());
        Assessment assessment = assess(snapshot);
        latest.set(assessment);
        try {
            telemetry.observeSlo(snapshot, assessment.state(),
                    assessment.retentionSuccessAge(), assessment.oldestOverdueJobAge(),
                    assessment.oldestExpiredTombstoneAge());
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability job retention SLO telemetry refresh failed");
        }
    }

    /** Returns the latest payload-free Actuator health view, observing on first access. */
    @Override
    public Health health() {
        Assessment assessment = latest.get();
        if (assessment == null) {
            refresh();
            assessment = latest.get();
        }
        Health.Builder builder = Health.status(status(assessment.state()))
                .withDetail("state", assessment.state().name())
                .withDetail("violations", assessment.violations());
        if (assessment.observedAt() != null) {
            builder.withDetail("observedAt", assessment.observedAt().toString())
                    .withDetail("overdueJobs", assessment.overdueJobs())
                    .withDetail("expiredTombstones", assessment.expiredTombstones())
                    .withDetail("retentionSuccessAgeSeconds",
                            secondsOrUnknown(assessment.retentionSuccessAge()))
                    .withDetail("oldestOverdueJobAgeSeconds",
                            secondsOrUnknown(assessment.oldestOverdueJobAge()))
                    .withDetail("oldestExpiredTombstoneAgeSeconds",
                            secondsOrUnknown(assessment.oldestExpiredTombstoneAge()));
        }
        return builder.build();
    }

    private Assessment assess(TestSuiteStabilityJobRetentionSnapshot snapshot) {
        List<String> violations = new ArrayList<>();
        Duration startupAge = age(firstObservedAt.get(), snapshot.observedAt());
        Duration successAge = age(snapshot.lastSuccessAt(), snapshot.observedAt());
        Duration overdueAge = age(
                snapshot.oldestOverdueJobExpiresAt(), snapshot.observedAt());
        Duration expiredTombstoneAge = age(
                snapshot.oldestExpiredTombstoneExpiresAt(), snapshot.observedAt());
        boolean initializing = false;
        if (successAge == null) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.RETENTION_NEVER_SUCCEEDED.name());
            }
        } else if (successAge.compareTo(policy.maxRetentionStaleness()) > 0) {
            violations.add(Violation.RETENTION_STALE.name());
        }
        if (snapshot.overdueJobRecords() > policy.maxOverdueJobs()) {
            violations.add(Violation.JOB_RETENTION_BACKLOG_EXCEEDED.name());
        }
        if (overdueAge != null
                && overdueAge.compareTo(policy.maxOldestOverdueJobAge()) > 0) {
            violations.add(Violation.JOB_RETENTION_BACKLOG_STALE.name());
        }
        if (snapshot.expiredTombstoneRecords() > policy.maxExpiredTombstones()) {
            violations.add(Violation.TOMBSTONE_PURGE_BACKLOG_EXCEEDED.name());
        }
        if (expiredTombstoneAge != null
                && expiredTombstoneAge.compareTo(
                policy.maxOldestExpiredTombstoneAge()) > 0) {
            violations.add(Violation.TOMBSTONE_PURGE_BACKLOG_STALE.name());
        }
        State state = !violations.isEmpty()
                ? State.SLO_VIOLATED : initializing ? State.INITIALIZING : State.HEALTHY;
        return new Assessment(state, List.copyOf(violations), snapshot.observedAt(),
                snapshot.overdueJobRecords(), snapshot.expiredTombstoneRecords(),
                successAge, overdueAge, expiredTombstoneAge);
    }

    private void observeStoreUnavailable() {
        try {
            telemetry.observeStoreUnavailable();
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability job retention store-unavailable telemetry failed");
        }
    }

    private static Duration age(Instant earlier, Instant observedAt) {
        if (earlier == null) {
            return null;
        }
        Duration value = Duration.between(earlier, observedAt);
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static long secondsOrUnknown(Duration value) {
        return value == null ? -1 : value.toSeconds();
    }

    private static Status status(State state) {
        return switch (state) {
            case HEALTHY -> Status.UP;
            case INITIALIZING -> Status.UNKNOWN;
            case SLO_VIOLATED -> Status.OUT_OF_SERVICE;
            case STORE_UNAVAILABLE -> Status.DOWN;
        };
    }

    /** Stable aggregate health states suitable for readiness and alert routing. */
    public enum State {
        /** Retention freshness and both backlogs satisfy policy. */
        HEALTHY,
        /** No page completed yet, but startup remains inside its grace window. */
        INITIALIZING,
        /** One or more stable freshness or backlog policies are violated. */
        SLO_VIOLATED,
        /** The lifecycle store could not produce a trustworthy snapshot. */
        STORE_UNAVAILABLE
    }

    /** Stable machine-readable retention SLO failures. */
    public enum Violation {
        /** No retention page completed after startup grace. */
        RETENTION_NEVER_SUCCEEDED,
        /** The last committed retention page is older than policy. */
        RETENTION_STALE,
        /** Too many terminal jobs await detailed erasure. */
        JOB_RETENTION_BACKLOG_EXCEEDED,
        /** The oldest terminal job backlog exceeds its age policy. */
        JOB_RETENTION_BACKLOG_STALE,
        /** Too many expired request tombstones await physical purge. */
        TOMBSTONE_PURGE_BACKLOG_EXCEEDED,
        /** The oldest expired tombstone exceeds its purge-age policy. */
        TOMBSTONE_PURGE_BACKLOG_STALE,
        /** The retention store could not produce an aggregate snapshot. */
        RETENTION_STORE_UNAVAILABLE
    }

    /**
     * Stability-job retention SLO thresholds.
     *
     * @param observationInterval configured monitor schedule interval
     * @param startupGrace grace before missing first success becomes a violation
     * @param maxRetentionStaleness oldest acceptable committed-page success age
     * @param maxOverdueJobs largest acceptable terminal-job erasure backlog
     * @param maxOldestOverdueJobAge oldest acceptable terminal-job backlog age
     * @param maxExpiredTombstones largest acceptable tombstone purge backlog
     * @param maxOldestExpiredTombstoneAge oldest acceptable expired-tombstone age
     */
    public record Policy(
            Duration observationInterval,
            Duration startupGrace,
            Duration maxRetentionStaleness,
            long maxOverdueJobs,
            Duration maxOldestOverdueJobAge,
            long maxExpiredTombstones,
            Duration maxOldestExpiredTombstoneAge) {

        /** Validates positive durations and non-negative inclusive backlog limits. */
        public Policy {
            positive(observationInterval, "observationInterval");
            positive(startupGrace, "startupGrace");
            positive(maxRetentionStaleness, "maxRetentionStaleness");
            positive(maxOldestOverdueJobAge, "maxOldestOverdueJobAge");
            positive(maxOldestExpiredTombstoneAge,
                    "maxOldestExpiredTombstoneAge");
            if (maxOverdueJobs < 0 || maxExpiredTombstones < 0) {
                throw new IllegalArgumentException(
                        "Stability-job retention SLO limits must be non-negative");
            }
            if (observationInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || observationInterval.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException(
                        "observationInterval must be between PT1S and PT720H");
            }
        }

        private static void positive(Duration value, String name) {
            Duration safe = Objects.requireNonNull(value, name);
            if (safe.isZero() || safe.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    private record Assessment(
            State state,
            List<String> violations,
            Instant observedAt,
            long overdueJobs,
            long expiredTombstones,
            Duration retentionSuccessAge,
            Duration oldestOverdueJobAge,
            Duration oldestExpiredTombstoneAge) {

        private static Assessment storeUnavailable() {
            return new Assessment(State.STORE_UNAVAILABLE,
                    List.of(Violation.RETENTION_STORE_UNAVAILABLE.name()),
                    null, 0, 0, null, null, null);
        }
    }
}
