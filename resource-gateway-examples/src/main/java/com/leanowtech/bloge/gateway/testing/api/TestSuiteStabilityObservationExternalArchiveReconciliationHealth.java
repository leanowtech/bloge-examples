package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed operational readiness for external observation-archive reconciliation.
 *
 * <p>Liveness alone is insufficient: a timer may run while one durable stage is stalled or while
 * retained evidence silently exceeds policy. Each refresh therefore combines process-local
 * scheduler freshness with fingerprint-verified, database-clock inventory, comparison, finding,
 * and retention snapshots. The monitor emits only bounded aggregate counts, durations, closed
 * status labels, and violation codes. Authority, object, cursor, lease, topology, and fingerprint
 * identities never cross this boundary.</p>
 *
 * <p>Open findings are business outcomes and do not veto readiness. Readiness fails only when the
 * control loop cannot produce trustworthy, fresh evidence or enforce its configured lifecycle.</p>
 */
public final class TestSuiteStabilityObservationExternalArchiveReconciliationHealth
        implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityObservationExternalArchiveReconciliationHealth.class);
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveReconciliationHealth.v1";

    private final TestSuiteStabilityObservationExternalArchiveReconciliationService service;
    private final TestSuiteStabilityObservationExternalArchiveReconciliationScheduler scheduler;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            inventories;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            comparisons;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            retention;
    private final Policy policy;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates a production-clock readiness monitor for an explicitly enabled test/staging loop.
     *
     * @param service configured bounded authority membership
     * @param scheduler process-local autonomous driver
     * @param inventories durable remote inventory progress
     * @param comparisons durable frozen comparison progress
     * @param findings durable governed finding projection progress
     * @param retention database-fenced derived-evidence lifecycle
     * @param policy finite freshness, stall, and backlog thresholds
     */
    public TestSuiteStabilityObservationExternalArchiveReconciliationHealth(
            TestSuiteStabilityObservationExternalArchiveReconciliationService service,
            TestSuiteStabilityObservationExternalArchiveReconciliationScheduler scheduler,
            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                    inventories,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    comparisons,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    retention,
            Policy policy) {
        this(service, scheduler, inventories, comparisons, findings, retention, policy,
                Clock.systemUTC());
    }

    /** Package-visible clock seam keeps startup and process-liveness tests deterministic. */
    TestSuiteStabilityObservationExternalArchiveReconciliationHealth(
            TestSuiteStabilityObservationExternalArchiveReconciliationService service,
            TestSuiteStabilityObservationExternalArchiveReconciliationScheduler scheduler,
            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                    inventories,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    comparisons,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    retention,
            Policy policy,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.findings = Objects.requireNonNull(findings, "findings");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    /** Refreshes all protocol-bounded authorities and fails the aggregate closed on ambiguity. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.health-observation-interval-ms:30000}")
    public void refresh() {
        try {
            latest.set(assess(clock.instant()));
        } catch (RuntimeException unavailable) {
            latest.set(Assessment.storeUnavailable(clock.instant()));
            log.warn("External archive reconciliation readiness observation failed; health is "
                    + "fail-closed until all bounded aggregate snapshots verify");
        }
    }

    /** Returns the latest identity-free readiness view, refreshing synchronously on first access. */
    @Override
    public Health health() {
        Assessment assessment = current();
        Health.Builder builder = Health.status(status(assessment.state()))
                .withDetail("schemaVersion", SCHEMA_VERSION)
                .withDetail("state", assessment.state().name())
                .withDetail("violations", assessment.violations())
                .withDetail("observedAt", assessment.observedAt().toString())
                .withDetail("configuredAuthorityCount", assessment.authorityCount());
        if (assessment.schedulerStatus() != null) {
            builder.withDetail("schedulerStatus", assessment.schedulerStatus().name())
                    .withDetail("schedulerSequence", assessment.schedulerSequence())
                    .withDetail("schedulerLastAttemptAgeSeconds",
                            secondsOrUnknown(assessment.schedulerLastAttemptAge()))
                    .withDetail("schedulerLastSuccessAgeSeconds",
                            secondsOrUnknown(assessment.schedulerLastSuccessAge()))
                    .withDetail("consecutiveUnhealthyTicks",
                            assessment.consecutiveUnhealthyTicks())
                    .withDetail("activeInventoryCycles", assessment.activeInventoryCycles())
                    .withDetail("activeComparisons", assessment.activeComparisons())
                    .withDetail("activeFindingProjections",
                            assessment.activeFindingProjections())
                    .withDetail("staleStages", assessment.staleStages())
                    .withDetail("authoritiesWithoutCompletedEvidence",
                            assessment.authoritiesWithoutCompletedEvidence())
                    .withDetail("oldestCompletedEvidenceAgeSeconds",
                            secondsOrUnknown(assessment.oldestCompletedEvidenceAge()))
                    .withDetail("openFindings", assessment.openFindings())
                    .withDetail("overdueResolvedFindings",
                            assessment.overdueResolvedFindings())
                    .withDetail("overdueFindingArchives",
                            assessment.overdueArchives())
                    .withDetail("overdueFindingEvidence",
                            assessment.overdueEvidence())
                    .withDetail("retentionLastSuccessAgeSeconds",
                            secondsOrUnknown(assessment.retentionLastSuccessAge()));
        }
        return builder.build();
    }

    /**
     * Returns the time-sensitive capability projection without triggering remote network I/O.
     *
     * @return configured and current readiness facts safe for the integration capability probe
     */
    public Descriptor descriptor() {
        Assessment assessment = current();
        return new Descriptor("", true, assessment.state() == State.HEALTHY,
                assessment.state().name(), assessment.violations(), assessment.observedAt(),
                assessment.authorityCount());
    }

    private Assessment current() {
        Assessment assessment = latest.get();
        if (assessment == null) {
            refresh();
            assessment = latest.get();
        }
        return assessment;
    }

    private Assessment assess(Instant processNow) {
        List<String> authorities = service.authorities();
        TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickResult tick =
                scheduler.latest();
        LinkedHashSet<Violation> violations = new LinkedHashSet<>();
        Duration startupAge = age(startedAt, processNow);
        boolean initializing = false;

        Duration lastAttemptAge = age(tick.attemptedAt(), processNow);
        Duration lastSuccessAge = age(tick.lastSuccessfulAt(), processNow);
        if (tick.status()
                == TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                .NOT_RUN) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.SCHEDULER_NEVER_SUCCEEDED);
            }
        }
        if (tick.consecutiveUnhealthyTicks() > policy.maximumConsecutiveUnhealthyTicks()) {
            violations.add(Violation.SCHEDULER_FAILURE_BUDGET_EXCEEDED);
        }
        if (lastSuccessAge == null) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.SCHEDULER_NEVER_SUCCEEDED);
            }
        } else if (lastSuccessAge.compareTo(policy.maximumSchedulerStaleness()) > 0) {
            violations.add(Violation.SCHEDULER_STALE);
        }

        int activeInventories = 0;
        int activeComparisons = 0;
        int activeProjections = 0;
        int staleStages = 0;
        int withoutCompletedEvidence = 0;
        Duration oldestEvidenceAge = null;
        for (String authority : authorities) {
            var inventory = inventories.operationalSnapshot(authority);
            var comparison = comparisons.operationalSnapshot(authority);
            var finding = findings.operationalSnapshot(authority);
            if (inventory.activeCycle()) {
                activeInventories++;
                if (stale(inventory.activeCycleUpdatedAt(), inventory.observedAt())) {
                    staleStages++;
                    violations.add(Violation.INVENTORY_STAGE_STALLED);
                }
            }
            if (comparison.activeComparison()) {
                activeComparisons++;
                if (stale(comparison.activeUpdatedAt(), comparison.observedAt())) {
                    staleStages++;
                    violations.add(Violation.COMPARISON_STAGE_STALLED);
                }
            }
            if (finding.activeProjection()) {
                activeProjections++;
                if (stale(finding.activeUpdatedAt(), finding.observedAt())) {
                    staleStages++;
                    violations.add(Violation.FINDING_STAGE_STALLED);
                }
            }
            Duration evidenceAge = age(finding.lastCompletedAt(), finding.observedAt());
            if (evidenceAge == null) {
                withoutCompletedEvidence++;
            } else {
                oldestEvidenceAge = maximum(oldestEvidenceAge, evidenceAge);
                if (evidenceAge.compareTo(policy.maximumCompletedEvidenceAge()) > 0) {
                    violations.add(Violation.COMPLETED_EVIDENCE_STALE);
                }
            }
        }
        if (withoutCompletedEvidence > 0) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.COMPLETED_EVIDENCE_NEVER_PRODUCED);
            }
        }

        var retentionSnapshot = retention.operationalSnapshot(
                policy.resolvedRetention(), policy.archiveRetention(),
                policy.evidenceRetention());
        Duration retentionSuccessAge = age(
                retentionSnapshot.lastSuccessAt(), retentionSnapshot.observedAt());
        if (retentionSuccessAge == null) {
            if (startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else {
                violations.add(Violation.RETENTION_NEVER_SUCCEEDED);
            }
        } else if (retentionSuccessAge.compareTo(policy.maximumRetentionStaleness()) > 0) {
            violations.add(Violation.RETENTION_STALE);
        }
        if (retentionSnapshot.overdueResolvedFindings()
                > policy.maximumOverdueResolvedFindings()) {
            violations.add(Violation.RESOLVED_FINDING_BACKLOG_EXCEEDED);
        }
        if (retentionSnapshot.overdueArchives() > policy.maximumOverdueArchives()) {
            violations.add(Violation.FINDING_ARCHIVE_BACKLOG_EXCEEDED);
        }
        if (retentionSnapshot.overdueEvidence() > policy.maximumOverdueEvidence()) {
            violations.add(Violation.FINDING_EVIDENCE_BACKLOG_EXCEEDED);
        }

        State state = !violations.isEmpty()
                ? State.SLO_VIOLATED : initializing ? State.INITIALIZING : State.HEALTHY;
        List<String> violationNames = violations.stream().map(Enum::name).toList();
        return new Assessment(state, violationNames, processNow, authorities.size(), tick.status(),
                tick.sequence(), tick.consecutiveUnhealthyTicks(), lastAttemptAge, lastSuccessAge,
                activeInventories, activeComparisons, activeProjections, staleStages,
                withoutCompletedEvidence, oldestEvidenceAge, retentionSnapshot.openFindings(),
                retentionSnapshot.overdueResolvedFindings(), retentionSnapshot.overdueArchives(),
                retentionSnapshot.overdueEvidence(), retentionSuccessAge);
    }

    private boolean stale(Instant updatedAt, Instant observedAt) {
        return age(updatedAt, observedAt).compareTo(policy.maximumStageIdle()) > 0;
    }

    private static Duration maximum(Duration left, Duration right) {
        return left == null || right.compareTo(left) > 0 ? right : left;
    }

    private static Duration age(Instant earlier, Instant later) {
        if (earlier == null) {
            return null;
        }
        Duration value = Duration.between(earlier, later);
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

    /** Closed aggregate states used by Actuator and capability truth. */
    public enum State {
        /** Scheduler, all durable stages, evidence freshness, and retention satisfy policy. */
        HEALTHY,
        /** The bounded startup grace permits the first complete control-loop pass. */
        INITIALIZING,
        /** One or more stable operational SLO limits are violated. */
        SLO_VIOLATED,
        /** Membership or a durable aggregate snapshot could not be verified. */
        STORE_UNAVAILABLE
    }

    /** Stable machine-readable root causes; business finding outcomes are deliberately absent. */
    public enum Violation {
        /** No all-authority scheduler pass completed before startup grace expired. */
        SCHEDULER_NEVER_SUCCEEDED,
        /** The latest complete all-authority pass is older than policy. */
        SCHEDULER_STALE,
        /** Consecutive degraded, failed, or overlapping ticks exceed the transient budget. */
        SCHEDULER_FAILURE_BUDGET_EXCEEDED,
        /** A durable inventory cycle has made no bounded page progress within policy. */
        INVENTORY_STAGE_STALLED,
        /** A durable frozen comparison has made no bounded page progress within policy. */
        COMPARISON_STAGE_STALLED,
        /** A durable finding projection has made no bounded page progress within policy. */
        FINDING_STAGE_STALLED,
        /** At least one authority produced no replay-verified finding evidence after grace. */
        COMPLETED_EVIDENCE_NEVER_PRODUCED,
        /** At least one authority's latest replay-verified evidence is older than policy. */
        COMPLETED_EVIDENCE_STALE,
        /** No derived finding/evidence retention page committed before startup grace expired. */
        RETENTION_NEVER_SUCCEEDED,
        /** The last committed retention page is older than policy. */
        RETENTION_STALE,
        /** Too many resolved findings exceeded their active operational window. */
        RESOLVED_FINDING_BACKLOG_EXCEEDED,
        /** Too many finding archives exceeded their governed archive window. */
        FINDING_ARCHIVE_BACKLOG_EXCEEDED,
        /** Too many completed projections exceeded their evidence window. */
        FINDING_EVIDENCE_BACKLOG_EXCEEDED,
        /** A membership or durable aggregate read could not be verified. */
        RECONCILIATION_STORE_UNAVAILABLE
    }

    /**
     * Finite readiness policy with schedule-aware lower bounds.
     *
     * @param observationInterval health refresh interval
     * @param startupGrace maximum first-pass initialization time
     * @param schedulerInterval configured reconciliation interval
     * @param maximumSchedulerStaleness maximum age of a complete all-authority pass
     * @param maximumConsecutiveUnhealthyTicks tolerated transient scheduler attempts
     * @param maximumStageIdle maximum database-clock age without active-stage progress
     * @param maximumCompletedEvidenceAge maximum age of latest completed evidence per authority
     * @param retentionInterval configured derived-evidence retention interval
     * @param maximumRetentionStaleness maximum age of a committed retention page
     * @param resolvedRetention resolved finding lifecycle window
     * @param archiveRetention archived finding lifecycle window
     * @param evidenceRetention finding projection evidence lifecycle window
     * @param maximumOverdueResolvedFindings tolerated eligible resolved backlog
     * @param maximumOverdueArchives tolerated eligible archive backlog
     * @param maximumOverdueEvidence tolerated eligible evidence backlog
     */
    public record Policy(
            Duration observationInterval,
            Duration startupGrace,
            Duration schedulerInterval,
            Duration maximumSchedulerStaleness,
            long maximumConsecutiveUnhealthyTicks,
            Duration maximumStageIdle,
            Duration maximumCompletedEvidenceAge,
            Duration retentionInterval,
            Duration maximumRetentionStaleness,
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration evidenceRetention,
            long maximumOverdueResolvedFindings,
            long maximumOverdueArchives,
            long maximumOverdueEvidence) {
        /** Validates bounded schedules, lifecycle windows, and non-negative backlog limits. */
        public Policy {
            observationInterval = bounded(observationInterval, "observationInterval",
                    Duration.ofSeconds(1), Duration.ofDays(1));
            startupGrace = bounded(startupGrace, "startupGrace",
                    Duration.ofSeconds(1), Duration.ofDays(7));
            schedulerInterval = bounded(schedulerInterval, "schedulerInterval",
                    Duration.ofSeconds(1), Duration.ofDays(7));
            maximumSchedulerStaleness = bounded(maximumSchedulerStaleness,
                    "maximumSchedulerStaleness", schedulerInterval, Duration.ofDays(30));
            maximumStageIdle = bounded(maximumStageIdle, "maximumStageIdle",
                    schedulerInterval, Duration.ofDays(30));
            maximumCompletedEvidenceAge = bounded(maximumCompletedEvidenceAge,
                    "maximumCompletedEvidenceAge", schedulerInterval, Duration.ofDays(3650));
            retentionInterval = bounded(retentionInterval, "retentionInterval",
                    Duration.ofSeconds(1), Duration.ofDays(7));
            maximumRetentionStaleness = bounded(maximumRetentionStaleness,
                    "maximumRetentionStaleness", retentionInterval, Duration.ofDays(30));
            resolvedRetention = bounded(resolvedRetention, "resolvedRetention",
                    Duration.ofHours(1), Duration.ofDays(3650));
            archiveRetention = bounded(archiveRetention, "archiveRetention",
                    Duration.ofDays(1), Duration.ofDays(3650));
            evidenceRetention = bounded(evidenceRetention, "evidenceRetention",
                    Duration.ofDays(1), Duration.ofDays(3650));
            if (startupGrace.compareTo(schedulerInterval) < 0
                    || startupGrace.compareTo(retentionInterval) < 0) {
                throw new IllegalArgumentException(
                        "startupGrace must cover both configured scheduler intervals");
            }
            if (maximumConsecutiveUnhealthyTicks < 0
                    || maximumConsecutiveUnhealthyTicks > 100
                    || maximumOverdueResolvedFindings < 0
                    || maximumOverdueArchives < 0 || maximumOverdueEvidence < 0
                    || maximumOverdueResolvedFindings > 1_000_000
                    || maximumOverdueArchives > 1_000_000
                    || maximumOverdueEvidence > 1_000_000) {
                throw new IllegalArgumentException(
                        "External reconciliation health counts are outside bounded policy");
            }
        }

        private static Duration bounded(
                Duration value, String name, Duration minimum, Duration maximum) {
            Duration exact = Objects.requireNonNull(value, name);
            if (exact.compareTo(minimum) < 0 || exact.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " is outside bounded policy");
            }
            return exact;
        }
    }

    /**
     * Key- and identity-free capability state for the unauthenticated integration probe.
     *
     * @param schemaVersion descriptor schema
     * @param configured whether this profile assembled reconciliation
     * @param ready whether the latest complete assessment is healthy
     * @param state closed readiness or disabled state
     * @param violations stable aggregate operational violations
     * @param observedAt latest local assessment time, or absent when disabled
     * @param authorityCount configured bounded authority count
     */
    public record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean ready,
            String state,
            List<String> violations,
            Instant observedAt,
            int authorityCount) {
        /** Normalizes and validates public capability facts. */
        public Descriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            state = Objects.requireNonNullElse(state, "").trim();
            violations = violations == null ? List.of() : List.copyOf(violations);
            boolean knownState = "DISABLED".equals(state);
            for (State candidate : State.values()) {
                knownState |= candidate.name().equals(state);
            }
            LinkedHashSet<String> uniqueViolations = new LinkedHashSet<>();
            for (String violation : violations) {
                String exact = Objects.requireNonNullElse(violation, "").trim();
                try {
                    Violation.valueOf(exact);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException(
                            "Unknown external reconciliation capability violation", unknown);
                }
                if (!uniqueViolations.add(exact)) {
                    throw new IllegalArgumentException(
                            "Duplicate external reconciliation capability violation");
                }
            }
            violations = List.copyOf(uniqueViolations);
            boolean healthy = State.HEALTHY.name().equals(state);
            boolean initializing = State.INITIALIZING.name().equals(state);
            boolean sloViolated = State.SLO_VIOLATED.name().equals(state);
            boolean storeUnavailable = State.STORE_UNAVAILABLE.name().equals(state);
            if (authorityCount < 0
                    || authorityCount
                    > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS
                    || !knownState
                    || !configured && (ready || observedAt != null || authorityCount != 0)
                    || !configured && (!"DISABLED".equals(state) || !violations.isEmpty())
                    || configured && ("DISABLED".equals(state) || observedAt == null)
                    || configured && ready != healthy
                    || (healthy || initializing) && !violations.isEmpty()
                    || sloViolated && violations.isEmpty()
                    || storeUnavailable && !violations.equals(
                    List.of(Violation.RECONCILIATION_STORE_UNAVAILABLE.name()))
                    || healthy && authorityCount == 0) {
                throw new IllegalArgumentException(
                        "Invalid external reconciliation capability descriptor");
            }
        }

        /** @return capability truth when the profile or feature is not assembled */
        public static Descriptor unavailable() {
            return new Descriptor("", false, false, "DISABLED", List.of(), null, 0);
        }
    }

    private record Assessment(
            State state,
            List<String> violations,
            Instant observedAt,
            int authorityCount,
            TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                    schedulerStatus,
            long schedulerSequence,
            long consecutiveUnhealthyTicks,
            Duration schedulerLastAttemptAge,
            Duration schedulerLastSuccessAge,
            int activeInventoryCycles,
            int activeComparisons,
            int activeFindingProjections,
            int staleStages,
            int authoritiesWithoutCompletedEvidence,
            Duration oldestCompletedEvidenceAge,
            long openFindings,
            long overdueResolvedFindings,
            long overdueArchives,
            long overdueEvidence,
            Duration retentionLastSuccessAge) {
        private Assessment {
            Objects.requireNonNull(state, "state");
            violations = List.copyOf(violations);
            Objects.requireNonNull(observedAt, "observedAt");
        }

        private static Assessment storeUnavailable(Instant observedAt) {
            return new Assessment(State.STORE_UNAVAILABLE,
                    List.of(Violation.RECONCILIATION_STORE_UNAVAILABLE.name()), observedAt,
                    0, null, 0, 0, null, null, 0, 0, 0, 0, 0, null,
                    0, 0, 0, 0, null);
        }
    }
}
