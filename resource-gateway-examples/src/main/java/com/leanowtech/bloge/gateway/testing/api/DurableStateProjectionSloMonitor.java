package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
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
 * Fail-closed SLO assessor for the durable-state projection control plane.
 *
 * <p>Assessment ages use the database observation clock, avoiding replica-clock skew. Health
 * details expose stable violation codes and aggregate counts only; storage exception messages,
 * finding identities, tokens, and business payloads are never retained.</p>
 */
public final class DurableStateProjectionSloMonitor implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(
            DurableStateProjectionSloMonitor.class);

    private final DatabaseDurableStateProjectionControlPlane controlPlane;
    private final DurableStateProjectionTelemetry telemetry;
    private final Policy policy;
    private final AtomicReference<Instant> firstObservedAt = new AtomicReference<>();
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates a profile-gated monitor backed by durable database observations.
     *
     * @param controlPlane durable aggregate snapshot authority
     * @param telemetry bounded-cardinality gauge adapter
     * @param policy freshness, age, and backlog limits
     */
    public DurableStateProjectionSloMonitor(
            DatabaseDurableStateProjectionControlPlane controlPlane,
            DurableStateProjectionTelemetry telemetry,
            Policy policy) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Refreshes health and gauges from one transactionally consistent operational snapshot. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.durable.projection-slo.observation-interval-ms:30000}")
    public void refresh() {
        try {
            DatabaseDurableStateProjectionControlPlane.OperationalSnapshot snapshot =
                    controlPlane.operationalSnapshot(
                            policy.resolvedRetention(), policy.archiveRetention());
            firstObservedAt.compareAndSet(null, snapshot.observedAt());
            Assessment assessment = assess(snapshot);
            latest.set(assessment);
            telemetry.observe(snapshot, assessment.state(),
                    assessment.reconciliationAge(), assessment.retentionAge());
        } catch (RuntimeException unavailable) {
            latest.set(Assessment.storeUnavailable());
            telemetry.observeStoreUnavailable();
            log.warn("Durable-state projection SLO observation failed; "
                    + "health is fail-closed until a database snapshot succeeds");
        }
    }

    /** Returns the latest payload-free Actuator health state, observing once on first access. */
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
                    .withDetail("unresolvedFindings", assessment.unresolvedFindings())
                    .withDetail("overdueResolvedFindings",
                            assessment.overdueResolvedFindings())
                    .withDetail("overdueArchiveRecords", assessment.overdueArchiveRecords())
                    .withDetail("reconciliationSuccessAgeSeconds",
                            secondsOrUnknown(assessment.reconciliationAge()))
                    .withDetail("retentionSuccessAgeSeconds",
                            secondsOrUnknown(assessment.retentionAge()));
        }
        return builder.build();
    }

    private Assessment assess(
            DatabaseDurableStateProjectionControlPlane.OperationalSnapshot snapshot) {
        List<String> violations = new ArrayList<>();
        Duration reconciliationAge = age(
                snapshot.controlSnapshot().lastSuccessAt(), snapshot.observedAt());
        Duration retentionAge = age(
                snapshot.retentionSnapshot().lastSuccessAt(), snapshot.observedAt());
        Duration startupAge = age(firstObservedAt.get(), snapshot.observedAt());
        boolean initializing = false;
        if (reconciliationAge == null) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.RECONCILIATION_NEVER_SUCCEEDED.name());
            }
        } else if (reconciliationAge.compareTo(policy.maxReconciliationStaleness()) > 0) {
            violations.add(Violation.RECONCILIATION_STALE.name());
        }
        if (retentionAge == null) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.RETENTION_NEVER_SUCCEEDED.name());
            }
        } else if (retentionAge.compareTo(policy.maxRetentionStaleness()) > 0) {
            violations.add(Violation.RETENTION_STALE.name());
        }
        if (snapshot.unresolvedFindings() > policy.maxUnresolvedFindings()) {
            violations.add(Violation.UNRESOLVED_FINDING_LIMIT_EXCEEDED.name());
        }
        Duration unresolvedAge = age(snapshot.oldestUnresolvedAt(), snapshot.observedAt());
        if (unresolvedAge != null
                && unresolvedAge.compareTo(policy.maxUnresolvedAge()) > 0) {
            violations.add(Violation.UNRESOLVED_FINDING_AGE_EXCEEDED.name());
        }
        if (snapshot.overdueResolvedFindings() > policy.maxOverdueResolvedFindings()) {
            violations.add(Violation.RESOLVED_RETENTION_BACKLOG_EXCEEDED.name());
        }
        if (snapshot.overdueArchiveRecords() > policy.maxOverdueArchiveRecords()) {
            violations.add(Violation.ARCHIVE_PURGE_BACKLOG_EXCEEDED.name());
        }
        State state = !violations.isEmpty()
                ? State.SLO_VIOLATED : initializing ? State.INITIALIZING : State.HEALTHY;
        return new Assessment(state, List.copyOf(violations), snapshot.observedAt(),
                snapshot.unresolvedFindings(), snapshot.overdueResolvedFindings(),
                snapshot.overdueArchiveRecords(), reconciliationAge, retentionAge);
    }

    private static Duration age(Instant earlier, Instant observedAt) {
        if (earlier == null) {
            return null;
        }
        Duration age = Duration.between(earlier, observedAt);
        return age.isNegative() ? Duration.ZERO : age;
    }

    private static long secondsOrUnknown(Duration duration) {
        return duration == null ? -1 : duration.toSeconds();
    }

    private static Status status(State state) {
        return switch (state) {
            case HEALTHY -> Status.UP;
            case INITIALIZING -> Status.UNKNOWN;
            case SLO_VIOLATED -> Status.OUT_OF_SERVICE;
            case STORE_UNAVAILABLE -> Status.DOWN;
        };
    }

    /** Stable aggregate health states suitable for alerts and readiness policy. */
    public enum State {
        /** Both control loops and all backlogs satisfy policy. */
        HEALTHY,
        /** A loop has not completed but remains inside the database-observed startup grace. */
        INITIALIZING,
        /** One or more stable freshness, age, or backlog policies are violated. */
        SLO_VIOLATED,
        /** The durable projection store could not produce an operational snapshot. */
        STORE_UNAVAILABLE
    }

    /** Stable machine-readable SLO failures; enum names are the external codes. */
    public enum Violation {
        /** Reconciliation has no success after startup grace. */
        RECONCILIATION_NEVER_SUCCEEDED,
        /** The last reconciliation success is older than policy. */
        RECONCILIATION_STALE,
        /** Retention has no success after startup grace. */
        RETENTION_NEVER_SUCCEEDED,
        /** The last retention success is older than policy. */
        RETENTION_STALE,
        /** The unresolved owner queue exceeds its count limit. */
        UNRESOLVED_FINDING_LIMIT_EXCEEDED,
        /** The oldest unresolved finding exceeds its age limit. */
        UNRESOLVED_FINDING_AGE_EXCEEDED,
        /** Active resolved findings beyond retention exceed their count limit. */
        RESOLVED_RETENTION_BACKLOG_EXCEEDED,
        /** Archive records beyond retention exceed their count limit. */
        ARCHIVE_PURGE_BACKLOG_EXCEEDED,
        /** The durable store could not produce an operational snapshot. */
        PROJECTION_STORE_UNAVAILABLE
    }

    /**
     * Projection SLO thresholds. Count limits are inclusive; a larger observation violates SLO.
     *
     * @param resolvedRetention active resolved-finding retention used for backlog evaluation
     * @param archiveRetention archive retention used for purge-backlog evaluation
     * @param startupGrace database-observed grace before a missing success becomes a violation
     * @param maxReconciliationStaleness oldest acceptable reconciliation success age
     * @param maxRetentionStaleness oldest acceptable retention success age
     * @param maxUnresolvedFindings largest acceptable unresolved queue size
     * @param maxUnresolvedAge oldest acceptable unresolved finding age
     * @param maxOverdueResolvedFindings largest acceptable active retention backlog
     * @param maxOverdueArchiveRecords largest acceptable archive purge backlog
     */
    public record Policy(
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration startupGrace,
            Duration maxReconciliationStaleness,
            Duration maxRetentionStaleness,
            long maxUnresolvedFindings,
            Duration maxUnresolvedAge,
            long maxOverdueResolvedFindings,
            long maxOverdueArchiveRecords) {
        /** Validates positive time windows and non-negative backlog limits. */
        public Policy {
            positive(resolvedRetention, "resolvedRetention");
            positive(archiveRetention, "archiveRetention");
            positive(startupGrace, "startupGrace");
            positive(maxReconciliationStaleness, "maxReconciliationStaleness");
            positive(maxRetentionStaleness, "maxRetentionStaleness");
            positive(maxUnresolvedAge, "maxUnresolvedAge");
            if (maxUnresolvedFindings < 0 || maxOverdueResolvedFindings < 0
                    || maxOverdueArchiveRecords < 0) {
                throw new IllegalArgumentException("Projection SLO count limits must be non-negative");
            }
        }

        private static void positive(Duration value, String name) {
            if (Objects.requireNonNull(value, name).isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    private record Assessment(
            State state,
            List<String> violations,
            Instant observedAt,
            long unresolvedFindings,
            long overdueResolvedFindings,
            long overdueArchiveRecords,
            Duration reconciliationAge,
            Duration retentionAge) {
        private static Assessment storeUnavailable() {
            return new Assessment(State.STORE_UNAVAILABLE,
                    List.of(Violation.PROJECTION_STORE_UNAVAILABLE.name()),
                    null, 0, 0, 0, null, null);
        }
    }
}
