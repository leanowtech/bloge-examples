package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Operator-owned authority for the retry policy governing recorded trajectories.
 *
 * <p>Trajectory commands may reference a policy but cannot define one. Implementations must
 * return one atomic current generation for the exact capability and scope. An unavailable
 * provider fails publication and serving closed instead of trusting producer retry labels.</p>
 */
public interface CapabilityRetryPolicyProvider {
    /**
     * Reports whether the policy authority can currently serve trustworthy reads.
     *
     * @return true only while policy resolution is authoritative
     */
    boolean available();

    /**
     * Resolves the current retry policy for one exact capability.
     *
     * @param scope complete enterprise scope
     * @param capabilityRef exact capability revision
     * @return current atomic policy generation, or empty
     */
    Optional<RetryPolicy> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef);

    /**
     * Returns a fail-closed provider for deployments without retry governance.
     *
     * @return unavailable provider
     */
    static CapabilityRetryPolicyProvider unavailable() {
        return new CapabilityRetryPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<RetryPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef capabilityRef) {
                return Optional.empty();
            }
        };
    }

    /**
     * Atomic retry-policy generation used to validate an observed attempt sequence.
     *
     * @param scope complete governed scope
     * @param capabilityRef exact governed capability
     * @param policyRef exact current retry-policy artifact
     * @param maximumAttempts inclusive attempt ceiling
     * @param retryableErrorClasses closed normalized error-class constraint, empty if unused
     * @param retryableErrorCodes closed normalized error-code constraint, empty if unused
     */
    record RetryPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef policyRef,
            int maximumAttempts,
            Set<String> retryableErrorClasses,
            Set<String> retryableErrorCodes
    ) {
        /** Validates an exact bounded retry policy generation. */
        public RetryPolicy {
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            policyRef = ref(policyRef, "RETRY_POLICY", "policyRef");
            if (maximumAttempts < 2
                    || maximumAttempts
                    > CapabilityCorpusTrajectoryPublishRequest.MAXIMUM_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "maximumAttempts is outside the trajectory bound");
            }
            retryableErrorClasses = identifiers(
                    retryableErrorClasses, "retryableErrorClasses");
            retryableErrorCodes = identifiers(
                    retryableErrorCodes, "retryableErrorCodes");
            if (retryableErrorClasses.isEmpty() && retryableErrorCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "retry policy requires an error class or code");
            }
        }

        /**
         * Verifies the producer retry label and every configured owner-maintained constraint.
         *
         * <p>An empty dimension is unconstrained. When both class and code constraints are
         * configured, both must match; adding a policy dimension can therefore only narrow
         * authority.</p>
         *
         * @param error normalized observed failure
         * @return true only when the current policy permits another attempt
         */
        public boolean permits(
                CapabilityObservationEnvelope.NormalizedError error) {
            CapabilityObservationEnvelope.NormalizedError exact =
                    Objects.requireNonNull(error, "error");
            return exact.retryable()
                    && (retryableErrorClasses.isEmpty()
                    || retryableErrorClasses.contains(exact.errorClass()))
                    && (retryableErrorCodes.isEmpty()
                    || retryableErrorCodes.contains(exact.errorCode()));
        }
    }

    private static Set<String> identifiers(Set<String> values, String field) {
        if (values == null || values.size() > 1_000
                || values.stream().anyMatch(value -> value == null
                || !value.matches(
                "[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}"))) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return Set.copyOf(values);
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }
}
