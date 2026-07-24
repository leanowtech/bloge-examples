package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable aggregate idempotency, lease, and case-progress boundary for Scenario rehearsals.
 *
 * <p>The request row contains only complete enterprise scope, immutable content addresses,
 * scheduling counters, and lease coordinates. Case checkpoints contain the payload-free
 * {@link ScenarioCaseRehearsalResult}; they never persist TestSuite input, fixture values, node
 * input/output, or replay payload. Every checkpoint and terminal transition is fenced by the
 * current database-clock lease owner and monotonically increasing epoch.</p>
 */
public interface ScenarioRehearsalRunRepository {
    /** Aggregate request lifecycle state. */
    enum Status {
        ACTIVE,
        COMPLETED
    }

    /** Result of claiming one immutable aggregate request identity. */
    enum Outcome {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    /**
     * Immutable payload-free aggregate registration.
     *
     * @param scope complete enterprise scope
     * @param requestId caller idempotency identity
     * @param requestFingerprint hash of all effective command semantics
     * @param compiledPlanRef exact compiler-issued plan
     * @param runId stable aggregate run identity
     * @param totalCases exact number of ordered plan cases
     * @param retainUntil minimum coordination and progress retention boundary
     */
    record Registration(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String requestFingerprint,
            MirrorArtifactRef compiledPlanRef,
            String runId,
            int totalCases,
            Instant retainUntil
    ) {
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates one complete immutable registration. */
        public Registration {
            scope = Objects.requireNonNull(scope, "scope");
            requestId = required(requestId, "requestId");
            requestFingerprint = required(
                    requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be canonical SHA-256");
            }
            if (compiledPlanRef == null
                    || !"COMPILED_REHEARSAL_PLAN".equals(
                    compiledPlanRef.kind())) {
                throw new IllegalArgumentException(
                        "compiledPlanRef must identify a compiled rehearsal plan");
            }
            runId = required(runId, "runId");
            if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(runId)) {
                throw new IllegalArgumentException(
                        "runId must be a canonical Scenario rehearsal identity");
            }
            if (totalCases < 1 || totalCases > ScenarioPack.MAXIMUM_CASES) {
                throw new IllegalArgumentException(
                        "totalCases must be Scenario policy bounded");
            }
            retainUntil = Objects.requireNonNull(
                    retainUntil, "retainUntil");
        }
    }

    /**
     * Durable payload-free aggregate projection.
     *
     * @param registration immutable request semantics
     * @param status active or terminal state
     * @param leaseOwner current opaque worker-attempt identity
     * @param leaseEpoch monotonic fencing epoch
     * @param leaseExpiresAt exclusive database-clock authority boundary
     * @param nextCaseIndex first case not durably checkpointed
     * @param evidenceBundleFingerprint terminal signed evidence identity
     * @param lastFailureCode bounded structural failure from the last released attempt
     * @param startedAt first database-clock admission time
     * @param updatedAt latest durable transition time
     */
    record State(
            Registration registration,
            Status status,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            int nextCaseIndex,
            String evidenceBundleFingerprint,
            String lastFailureCode,
            Instant startedAt,
            Instant updatedAt
    ) {
        /** Enforces lifecycle, progress, and terminal-evidence correspondence. */
        public State {
            registration = Objects.requireNonNull(
                    registration, "registration");
            status = Objects.requireNonNull(status, "status");
            leaseOwner = required(leaseOwner, "leaseOwner");
            if (leaseEpoch < 1) {
                throw new IllegalArgumentException(
                        "leaseEpoch must be positive");
            }
            leaseExpiresAt = Objects.requireNonNull(
                    leaseExpiresAt, "leaseExpiresAt");
            if (nextCaseIndex < 0
                    || nextCaseIndex > registration.totalCases()) {
                throw new IllegalArgumentException(
                        "nextCaseIndex exceeds the registered case closure");
            }
            evidenceBundleFingerprint = normalized(
                    evidenceBundleFingerprint);
            lastFailureCode = normalized(lastFailureCode);
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (status == Status.COMPLETED
                    && (nextCaseIndex != registration.totalCases()
                    || evidenceBundleFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "completed rehearsal requires complete progress and evidence");
            }
            if (status == Status.ACTIVE
                    && !evidenceBundleFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "active rehearsal cannot expose terminal evidence");
            }
        }
    }

    /**
     * Fenced aggregate execution authority.
     *
     * @param scope complete enterprise scope
     * @param requestId aggregate request identity
     * @param leaseOwner opaque worker-attempt owner
     * @param leaseEpoch monotonic fencing epoch
     */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String leaseOwner,
            long leaseEpoch
    ) {
        /** Validates one complete fencing token. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            requestId = required(requestId, "requestId");
            leaseOwner = required(leaseOwner, "leaseOwner");
            if (leaseEpoch < 1) {
                throw new IllegalArgumentException(
                        "leaseEpoch must be positive");
            }
        }
    }

    /**
     * Database-clock claim outcome.
     *
     * @param outcome acquired, busy, or already completed
     * @param state current durable state
     * @param lease execution authority only for an acquired claim
     * @param retryAfterSeconds positive database-clock delay only when busy
     */
    record Claim(
            Outcome outcome,
            State state,
            Lease lease,
            long retryAfterSeconds
    ) {
        /** Enforces lease and retry-hint correspondence. */
        public Claim {
            outcome = Objects.requireNonNull(outcome, "outcome");
            state = Objects.requireNonNull(state, "state");
            if ((outcome == Outcome.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException(
                        "only an acquired claim may carry a lease");
            }
            if ((outcome == Outcome.IN_PROGRESS)
                    != (retryAfterSeconds > 0)) {
                throw new IllegalArgumentException(
                        "only an in-progress claim carries a retry delay");
            }
        }
    }

    /**
     * Creates, resumes, or observes one exact aggregate request.
     *
     * @throws ScenarioRehearsalRunRequestConflictException when the request id already has
     * different immutable semantics
     */
    Claim claim(
            Registration registration,
            String leaseOwner,
            Duration leaseDuration);

    /**
     * Reads the complete ordered payload-free checkpoint prefix for an acquired request.
     *
     * @throws ScenarioRehearsalLeaseLostException when the supplied lease is not current
     */
    List<ScenarioCaseRehearsalResult> progress(Lease lease);

    /**
     * Atomically appends the exact next case and advances the durable cursor.
     *
     * @throws ScenarioRehearsalLeaseLostException when the lease expired or was replaced
     */
    void checkpoint(Lease lease, ScenarioCaseRehearsalResult result);

    /**
     * Marks a fully checkpointed request terminal under the exact live lease.
     *
     * @return true when the active request became terminal; false when the lease was stale
     */
    boolean complete(Lease lease, String evidenceBundleFingerprint);

    /**
     * Releases one failed attempt so an exact retry can resume immediately.
     *
     * @return true when the exact active lease was released
     */
    boolean release(Lease lease, String failureCode);

    /** Finds one request state inside the exact enterprise scope. */
    Optional<State> find(CapabilitySnapshot.Scope scope, String requestId);

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
