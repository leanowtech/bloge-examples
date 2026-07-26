package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusted payload-isolated execution boundary for one durable read-only Shadow sample.
 *
 * <p>Implementations run in the TEE/data-plane trust domain, not in a browser or ordinary author
 * request. They must resolve the exact baseline binding and candidate plan, re-verify both
 * terminal source artifacts, enforce the exact current sampling/egress/kill-switch authorities,
 * expose no write credential, make no write attempt, normalize facts with the exact comparison
 * policy, and return only payload-free fingerprints and signed artifact references.</p>
 *
 * <p>The stable {@link Permit#executionId()} is reused across worker retries. Baseline connectors
 * must use it as their idempotency identity so lease loss cannot amplify physical source reads.</p>
 */
public interface ReadOnlyShadowDataPlane {
    /** @return whether every required source, policy, isolation, and verification authority is ready */
    boolean ready();

    /**
     * Executes one payload-isolated paired observation.
     *
     * @param permit exact durable request and cooperative lease control
     * @return payload-free verified source and typed-diff facts
     * @throws Failure bounded classified execution failure
     */
    ExecutionResult execute(Permit permit);

    /**
     * Exact logical-sample permit supplied to the trusted data plane.
     *
     * @param executionId stable idempotency identity across crash recovery
     * @param request immutable payload-free job request
     * @param attemptCount current physical worker attempt
     * @param deadlineAt database-authoritative deadline
     * @param control cooperative owner/epoch lease control
     */
    record Permit(
            String executionId,
            ReadOnlyShadowJobRequest request,
            int attemptCount,
            Instant deadlineAt,
            ExecutionControl control
    ) {
        /** Validates exact immutable execution coordinates. */
        public Permit {
            executionId = required(
                    executionId, "executionId");
            request = Objects.requireNonNull(
                    request, "request");
            deadlineAt = Objects.requireNonNull(
                    deadlineAt, "deadlineAt");
            control = Objects.requireNonNull(
                    control, "control");
            if (attemptCount < 1
                    || !deadlineAt.equals(
                    request.deadlineAt())) {
                throw new IllegalArgumentException(
                        "read-only Shadow execution permit is inconsistent");
            }
        }
    }

    /**
     * Cooperative lease boundary that must be checked immediately before every external read and
     * candidate execution.
     */
    interface ExecutionControl {
        /** @return current lease expiry according to the durable coordinator */
        Instant leaseExpiresAt();

        /**
         * Renews the owner/epoch fence.
         *
         * @return replacement database-clock lease expiry
         * @throws ReadOnlyShadowJobRepository.Violation when ownership or deadline was lost
         */
        Instant heartbeat();
    }

    /**
     * Payload-free verified result from the isolated data plane.
     *
     * @param accessProof runtime-observed zero-write proof matching the admitted grant
     * @param authorityProof double-observed grant, policy, and switch publication closure
     * @param sourceResolutionAttestationRef exact signed proof that both source refs were fetched
     *                                       and independently reverified
     * @param baseline exact verified baseline observation
     * @param candidate exact verified candidate observation
     * @param observedAt authoritative comparison time
     * @param results canonical typed normalized-fact comparisons
     */
    record ExecutionResult(
            ReadOnlyShadowComparison.AccessProof accessProof,
            ReadOnlyShadowComparison.AuthorityProof authorityProof,
            MirrorArtifactRef sourceResolutionAttestationRef,
            ReadOnlyShadowComparison.SourceObservation baseline,
            ReadOnlyShadowComparison.SourceObservation candidate,
            Instant observedAt,
            List<ReadOnlyShadowComparison.DimensionComparison> results
    ) {
        /** Requires complete bounded payload-free evidence coordinates. */
        public ExecutionResult {
            accessProof = Objects.requireNonNull(
                    accessProof, "accessProof");
            authorityProof = Objects.requireNonNull(
                    authorityProof, "authorityProof");
            sourceResolutionAttestationRef =
                    requireKind(
                            sourceResolutionAttestationRef,
                            "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                            "sourceResolutionAttestationRef");
            baseline = Objects.requireNonNull(
                    baseline, "baseline");
            candidate = Objects.requireNonNull(
                    candidate, "candidate");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            results = results == null
                    ? List.of() : List.copyOf(results);
            if (results.isEmpty()) {
                throw new IllegalArgumentException(
                        "read-only Shadow result must contain typed comparisons");
            }
        }
    }

    /** Bounded data-plane failure vocabulary safe for queue state and telemetry. */
    enum FailureReason {
        ADMISSION_AUTHORITY_UNAVAILABLE(true),
        BASELINE_SOURCE_UNAVAILABLE(true),
        CANDIDATE_RUNTIME_UNAVAILABLE(true),
        SOURCE_VERIFICATION_FAILED(false),
        GRANT_REVOKED(false),
        KILL_SWITCH_OPEN(false),
        EGRESS_DENIED(false),
        BUDGET_EXHAUSTED(true),
        CIRCUIT_OPEN(true),
        EXECUTION_ID_CONFLICT(false),
        WRITE_CAPABILITY_DETECTED(false),
        WRITE_ATTEMPT_DETECTED(false),
        NORMALIZATION_POLICY_UNAVAILABLE(true),
        NORMALIZATION_FAILED(false),
        LEASE_LOST(true),
        DEADLINE_EXCEEDED(false);

        private final boolean retryable;

        FailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        /** @return whether another fenced attempt may be admitted by server policy */
        public boolean retryable() {
            return retryable;
        }
    }

    /** Stable payload-free classified data-plane failure. */
    final class Failure extends RuntimeException {
        private final FailureReason reason;

        /** Creates one classified failure without accepting source exception text. */
        public Failure(FailureReason reason) {
            super("Read-only Shadow data plane failed: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable failure reason */
        public FailureReason reason() {
            return reason;
        }
    }

    /** Default fail-closed data plane used until an operator-owned connector is installed. */
    static ReadOnlyShadowDataPlane unavailable() {
        return new ReadOnlyShadowDataPlane() {
            @Override
            public boolean ready() {
                return false;
            }

            @Override
            public ExecutionResult execute(Permit permit) {
                Objects.requireNonNull(permit, "permit");
                throw new Failure(
                        FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
            }
        };
    }

    private static String required(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!Pattern.matches(
                "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}",
                normalized)) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }
}
