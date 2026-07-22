package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable payload-free idempotency and lease boundary for protected mirror execution requests.
 *
 * <p>The repository stores canonical fingerprints rather than request context. A lease is fenced
 * by both an unguessable owner and a monotonically increasing epoch. Completion must compare both
 * values so a worker that resumes after lease takeover cannot publish stale evidence. Implementations
 * installed with {@link MirrorEvidenceRepository} must participate in the same local transaction manager
 * when {@link MirrorRunCommitService} is used.</p>
 */
public interface MirrorRunRequestRepository {
    /** Active request lifecycle state. */
    enum Status {
        ACTIVE,
        COMPLETED
    }

    /** Result of acquiring one durable request identity. */
    enum Outcome {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    /**
     * Immutable payload-free request registration.
     *
     * @param scope complete enterprise scope
     * @param requestId caller idempotency identity
     * @param requestFingerprint hash of all effective execution-command semantics
     * @param contextFingerprint hash of the server-bound effective graph context
     * @param planId exact plan identity
     * @param planFingerprint exact plan generation
     * @param retainUntil minimum coordination-record retention boundary
     */
    record Registration(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String requestFingerprint,
            String contextFingerprint,
            String planId,
            String planFingerprint,
            Instant retainUntil
    ) {
        /** Validates one complete content-addressed registration. */
        public Registration {
            scope = Objects.requireNonNull(scope, "scope");
            requestId = required(requestId, "requestId");
            requestFingerprint = required(requestFingerprint, "requestFingerprint");
            contextFingerprint = required(contextFingerprint, "contextFingerprint");
            planId = required(planId, "planId");
            planFingerprint = required(planFingerprint, "planFingerprint");
            retainUntil = Objects.requireNonNull(retainUntil, "retainUntil");
        }
    }

    /**
     * Durable state projection that contains no request or result payload.
     *
     * @param registration immutable request registration
     * @param status active or terminal state
     * @param leaseOwner current lease owner; not an authorization credential
     * @param leaseEpoch monotonic fencing epoch
     * @param leaseExpiresAt wall-clock lease expiry
     * @param runId terminal run id when completed
     * @param evidenceBundleFingerprint terminal evidence bundle when completed
     * @param lastFailureCode bounded structural code from the last released attempt
     * @param createdAt first registration time
     * @param updatedAt last state transition time
     */
    record State(
            Registration registration,
            Status status,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            String runId,
            String evidenceBundleFingerprint,
            String lastFailureCode,
            Instant createdAt,
            Instant updatedAt
    ) {
        /** Validates one internally consistent durable projection. */
        public State {
            registration = Objects.requireNonNull(registration, "registration");
            status = Objects.requireNonNull(status, "status");
            leaseOwner = required(leaseOwner, "leaseOwner");
            if (leaseEpoch < 1) {
                throw new IllegalArgumentException("leaseEpoch must be positive");
            }
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            runId = normalized(runId);
            evidenceBundleFingerprint = normalized(evidenceBundleFingerprint);
            lastFailureCode = normalized(lastFailureCode);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (status == Status.COMPLETED
                    && (runId.isBlank() || evidenceBundleFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "completed mirror request requires terminal evidence identity");
            }
            if (status == Status.ACTIVE
                    && (!runId.isBlank() || !evidenceBundleFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "active mirror request must not expose terminal evidence identity");
            }
        }
    }

    /**
     * Fenced execution authority returned only for an acquired request.
     *
     * @param scope complete enterprise scope
     * @param requestId request identity
     * @param leaseOwner opaque process-attempt owner
     * @param leaseEpoch monotonic fencing epoch
     */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String leaseOwner,
            long leaseEpoch
    ) {
        /** Validates one complete lease token. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            requestId = required(requestId, "requestId");
            leaseOwner = required(leaseOwner, "leaseOwner");
            if (leaseEpoch < 1) {
                throw new IllegalArgumentException("leaseEpoch must be positive");
            }
        }
    }

    /**
     * Database-clock acquisition result.
     *
     * @param outcome acquisition outcome
     * @param state current durable state
     * @param lease acquired lease, only for {@link Outcome#ACQUIRED}
     * @param retryAfterSeconds bounded remaining database-clock delay for an in-progress claim
     */
    record Claim(Outcome outcome, State state, Lease lease, long retryAfterSeconds) {
        /** Enforces lease and retry-hint correspondence for every outcome. */
        public Claim {
            outcome = Objects.requireNonNull(outcome, "outcome");
            state = Objects.requireNonNull(state, "state");
            if ((outcome == Outcome.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException("only an acquired claim may carry a lease");
            }
            if ((outcome == Outcome.IN_PROGRESS) != (retryAfterSeconds > 0)) {
                throw new IllegalArgumentException(
                        "only an in-progress claim requires a positive retry delay");
            }
        }
    }

    /**
     * Creates, resumes, or observes one request under an exact immutable registration.
     *
     * @param registration payload-free immutable request semantics
     * @param leaseOwner unique owner for this execution attempt
     * @param leaseDuration database-clock lease covering execution and evidence finalization
     * @return acquired, still-running, or already-completed projection
     * @throws MirrorRunRequestConflictException when the request id already means something else
     */
    Claim claim(Registration registration, String leaseOwner, Duration leaseDuration);

    /**
     * Fenced terminal transition performed in the same transaction as evidence persistence.
     * The lease must still be unexpired according to repository-authoritative time; expiry revokes
     * authority even before another worker claims the next epoch.
     *
     * @return {@code true} when the exact active unexpired lease became terminal; otherwise false
     */
    boolean complete(Lease lease, String runId, String evidenceBundleFingerprint);

    /**
     * Releases a failed active attempt so an exact retry need not wait for lease expiry.
     *
     * @return {@code true} when the exact lease was released; {@code false} when stale
     */
    boolean release(Lease lease, String failureCode);

    /** Finds one payload-free request state inside the exact enterprise scope. */
    Optional<State> find(CapabilitySnapshot.Scope scope, String requestId);

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
