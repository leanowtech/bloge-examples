package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact-scope payload-free health projection for asynchronous batch evidence finalization.
 *
 * <p>The projection diagnoses control corruption, policy drift, actionable backlog, KMS latency,
 * retry pressure, and quarantine separately. It contains no job, tenant-neighbor, evidence,
 * payload, provider diagnostic, or exception material.</p>
 *
 * @param schemaVersion protocol version
 * @param scope complete authenticated enterprise scope
 * @param state aggregate severity
 * @param violations stable ordered root-cause indicators
 * @param observedAt database time used by every count and age
 * @param policyGeneration expected durable finalization policy generation
 * @param counts closed aggregate counters
 * @param ages database-clock backlog ages in milliseconds, zero when absent
 * @param thresholds server-owned policy projected for explainability
 */
public record ScenarioRehearsalBatchFinalizationHealth(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        State state,
        List<Violation> violations,
        Instant observedAt,
        long policyGeneration,
        Counts counts,
        Ages ages,
        Thresholds thresholds
) {
    /** Current finalization-health protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchFinalizationHealth.v1";

    /** Enforces a complete immutable health projection. */
    public ScenarioRehearsalBatchFinalizationHealth {
        schemaVersion = schemaVersion == null
                ? "" : schemaVersion.trim();
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch finalization health version");
        }
        scope = Objects.requireNonNull(scope, "scope");
        state = Objects.requireNonNull(state, "state");
        violations = violations == null
                ? List.of() : List.copyOf(violations);
        observedAt = Objects.requireNonNull(
                observedAt, "observedAt");
        counts = Objects.requireNonNull(counts, "counts");
        ages = Objects.requireNonNull(ages, "ages");
        thresholds = Objects.requireNonNull(
                thresholds, "thresholds");
        if (policyGeneration < 1
                || state == State.HEALTHY
                != violations.isEmpty()
                || state == State.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization health is inconsistent");
        }
    }

    /** Builds one exact-scope projection from a database-clock aggregate. */
    public static ScenarioRehearsalBatchFinalizationHealth from(
            CapabilitySnapshot.Scope scope,
            ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                    snapshot,
            ScenarioRehearsalBatchFinalizationHealthPolicy policy) {
        Assessment assessment = assess(snapshot, policy);
        if (assessment.state() == State.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "An unavailable observation cannot be exported as scope health");
        }
        return new ScenarioRehearsalBatchFinalizationHealth(
                "",
                scope,
                assessment.state(),
                assessment.violations(),
                snapshot.observedAt(),
                snapshot.expectedPolicyGeneration(),
                counts(snapshot),
                assessment.ages(),
                thresholds(policy));
    }

    /** Applies the shared deterministic SLO policy used by API, Actuator, and telemetry. */
    public static Assessment assess(
            ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                    snapshot,
            ScenarioRehearsalBatchFinalizationHealthPolicy policy) {
        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot exact =
                Objects.requireNonNull(snapshot, "snapshot");
        ScenarioRehearsalBatchFinalizationHealthPolicy limits =
                Objects.requireNonNull(policy, "policy");
        Ages ages = new Ages(
                ageMillis(
                        exact.oldestUnfinalizedCreatedAt(),
                        exact.observedAt()),
                ageMillis(
                        exact.oldestEligibleAt(),
                        exact.observedAt()),
                ageMillis(
                        exact.oldestQuarantinedAt(),
                        exact.observedAt()),
                ageMillis(
                        exact.oldestActiveSigningStartedAt(),
                        exact.observedAt()));
        List<Violation> violations = new ArrayList<>();
        boolean critical = false;
        if (exact.inconsistentRecordCount() > 0) {
            violations.add(Violation.CONTROL_RECORD_INCONSISTENT);
            critical = true;
        }
        if (exact.policyMismatchCount() > 0) {
            violations.add(Violation.POLICY_GENERATION_MISMATCH);
            critical = true;
        }
        if (exact.eligibleCount()
                > limits.maximumEligibleBacklog()) {
            violations.add(Violation.ELIGIBLE_BACKLOG_EXCEEDED);
            critical = true;
        }
        if (exact.eligibleCount() > 0
                && ages.oldestEligibleAgeMillis()
                > limits.maximumOldestEligibleAge().toMillis()) {
            violations.add(Violation.ELIGIBLE_BACKLOG_STALE);
            critical = true;
        }
        if (exact.staleSigningCount() > 0) {
            violations.add(Violation.STALE_SIGNING_LEASE);
            critical = true;
        }
        if (exact.signingCount() > 0
                && ages.oldestActiveSigningAgeMillis()
                > limits.maximumActiveSigningAge().toMillis()) {
            violations.add(Violation.ACTIVE_SIGNING_STALE);
        }
        if (exact.quarantinedCount()
                > limits.maximumQuarantinedBacklog()) {
            violations.add(Violation.QUARANTINE_PRESENT);
        }
        if (exact.quarantinedCount()
                > limits.criticalQuarantinedBacklog()) {
            violations.add(Violation.QUARANTINE_BACKLOG_CRITICAL);
            critical = true;
        }
        if (exact.signatureInvalidCount() > 0
                || exact.materialInvalidCount() > 0) {
            violations.add(Violation.NON_RETRYABLE_FAILURE_PRESENT);
        }
        if (exact.signerUnavailableCount()
                > limits.maximumSignerUnavailableBacklog()) {
            violations.add(Violation.SIGNER_UNAVAILABLE_PRESSURE);
            critical = true;
        }
        if (exact.controlUnavailableCount()
                > limits.maximumControlUnavailableBacklog()) {
            violations.add(Violation.CONTROL_UNAVAILABLE_PRESSURE);
            critical = true;
        }
        State state = critical
                ? State.CRITICAL
                : violations.isEmpty()
                ? State.HEALTHY
                : State.DEGRADED;
        return new Assessment(
                state,
                List.copyOf(violations),
                counts(exact),
                ages);
    }

    /** Creates a fail-closed assessment when the aggregate store cannot be trusted. */
    public static Assessment unavailable() {
        return new Assessment(
                State.UNAVAILABLE,
                List.of(Violation.STORE_UNAVAILABLE),
                Counts.empty(),
                Ages.empty());
    }

    private static Counts counts(
            ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                    snapshot) {
        return new Counts(
                snapshot.totalCount(),
                snapshot.pendingCount(),
                snapshot.signingCount(),
                snapshot.retryWaitCount(),
                snapshot.quarantinedCount(),
                snapshot.finalizedCount(),
                snapshot.unknownStateCount(),
                snapshot.eligibleCount(),
                snapshot.staleSigningCount(),
                snapshot.inconsistentRecordCount(),
                snapshot.policyMismatchCount(),
                snapshot.signerUnavailableCount(),
                snapshot.signatureInvalidCount(),
                snapshot.materialInvalidCount(),
                snapshot.controlUnavailableCount(),
                snapshot.maximumAttemptCount());
    }

    private static Thresholds thresholds(
            ScenarioRehearsalBatchFinalizationHealthPolicy policy) {
        return new Thresholds(
                policy.maximumEligibleBacklog(),
                policy.maximumOldestEligibleAge().toMillis(),
                policy.maximumActiveSigningAge().toMillis(),
                policy.maximumQuarantinedBacklog(),
                policy.criticalQuarantinedBacklog(),
                policy.maximumSignerUnavailableBacklog(),
                policy.maximumControlUnavailableBacklog());
    }

    private static long ageMillis(
            Instant since,
            Instant observedAt) {
        if (since == null) {
            return 0;
        }
        Duration age = Duration.between(since, observedAt);
        return age.isNegative() ? 0 : age.toMillis();
    }

    /** Aggregate health state. */
    public enum State {
        HEALTHY,
        DEGRADED,
        CRITICAL,
        UNAVAILABLE
    }

    /** Stable root-cause vocabulary suitable for gates, dashboards, and alerts. */
    public enum Violation {
        CONTROL_RECORD_INCONSISTENT,
        POLICY_GENERATION_MISMATCH,
        ELIGIBLE_BACKLOG_EXCEEDED,
        ELIGIBLE_BACKLOG_STALE,
        STALE_SIGNING_LEASE,
        ACTIVE_SIGNING_STALE,
        QUARANTINE_PRESENT,
        QUARANTINE_BACKLOG_CRITICAL,
        NON_RETRYABLE_FAILURE_PRESENT,
        SIGNER_UNAVAILABLE_PRESSURE,
        CONTROL_UNAVAILABLE_PRESSURE,
        STORE_UNAVAILABLE
    }

    /** Closed payload-free aggregate counters. */
    public record Counts(
            long total,
            long pending,
            long signing,
            long retryWait,
            long quarantined,
            long finalized,
            long unknownState,
            long eligible,
            long staleSigning,
            long inconsistentRecords,
            long policyMismatches,
            long signerUnavailable,
            long signatureInvalid,
            long materialInvalid,
            long controlUnavailable,
            int maximumAttemptCount
    ) {
        /** Rejects negative counters and incomplete state accounting. */
        public Counts {
            if (total < 0
                    || pending < 0
                    || signing < 0
                    || retryWait < 0
                    || quarantined < 0
                    || finalized < 0
                    || unknownState < 0
                    || eligible < 0
                    || staleSigning < 0
                    || inconsistentRecords < 0
                    || policyMismatches < 0
                    || signerUnavailable < 0
                    || signatureInvalid < 0
                    || materialInvalid < 0
                    || controlUnavailable < 0
                    || maximumAttemptCount < 0
                    || total != pending + signing + retryWait
                    + quarantined + finalized + unknownState) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization health counts are inconsistent");
            }
        }

        private static Counts empty() {
            return new Counts(
                    0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Database-clock aggregate ages in milliseconds. */
    public record Ages(
            long oldestUnfinalizedAgeMillis,
            long oldestEligibleAgeMillis,
            long oldestQuarantinedAgeMillis,
            long oldestActiveSigningAgeMillis
    ) {
        /** Rejects negative ages. */
        public Ages {
            if (oldestUnfinalizedAgeMillis < 0
                    || oldestEligibleAgeMillis < 0
                    || oldestQuarantinedAgeMillis < 0
                    || oldestActiveSigningAgeMillis < 0) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization health ages must be non-negative");
            }
        }

        private static Ages empty() {
            return new Ages(0, 0, 0, 0);
        }
    }

    /** Server-owned thresholds included so operators can explain every state. */
    public record Thresholds(
            long maximumEligibleBacklog,
            long maximumOldestEligibleAgeMillis,
            long maximumActiveSigningAgeMillis,
            long maximumQuarantinedBacklog,
            long criticalQuarantinedBacklog,
            long maximumSignerUnavailableBacklog,
            long maximumControlUnavailableBacklog
    ) {
        /** Rejects a malformed projected policy. */
        public Thresholds {
            if (maximumEligibleBacklog < 0
                    || maximumOldestEligibleAgeMillis < 1
                    || maximumActiveSigningAgeMillis < 1
                    || maximumQuarantinedBacklog < 0
                    || criticalQuarantinedBacklog
                    <= maximumQuarantinedBacklog
                    || maximumSignerUnavailableBacklog < 0
                    || maximumControlUnavailableBacklog < 0) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization health thresholds are invalid");
            }
        }
    }

    /** Internal shared assessment before an enterprise scope is attached. */
    public record Assessment(
            State state,
            List<Violation> violations,
            Counts counts,
            Ages ages
    ) {
        /** Enforces state and violation closure. */
        public Assessment {
            state = Objects.requireNonNull(state, "state");
            violations = violations == null
                    ? List.of() : List.copyOf(violations);
            counts = Objects.requireNonNull(counts, "counts");
            ages = Objects.requireNonNull(ages, "ages");
            if (state == State.HEALTHY
                    != violations.isEmpty()
                    || state == State.UNAVAILABLE
                    != violations.contains(
                    Violation.STORE_UNAVAILABLE)) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization assessment is inconsistent");
            }
        }
    }
}
