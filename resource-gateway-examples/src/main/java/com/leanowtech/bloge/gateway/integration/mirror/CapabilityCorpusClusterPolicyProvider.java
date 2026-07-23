package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Operator-owned authority for recorded-cluster publication risk policy.
 *
 * <p>The policy is resolved by exact scope and capability on every publication and, later, every
 * materialization. Numeric thresholds prevent a caller from choosing its own confidence bar;
 * {@code validationPolicy} lets the capability owner restrict approved match and projection paths
 * without teaching Resource Gateway business semantics.</p>
 */
public interface CapabilityCorpusClusterPolicyProvider {
    /**
     * Reports whether current policy lookup is usable.
     *
     * @return true only when fail-closed policy lookup is available
     */
    boolean available();

    /**
     * Resolves the current cluster policy for one exact capability.
     *
     * @param scope complete enterprise scope
     * @param capabilityRef exact capability
     * @return current policy, or empty when no cluster generalization is allowed
     */
    Optional<ClusterPolicy> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef);

    /**
     * Returns a fail-closed placeholder.
     *
     * @return unavailable policy authority
     */
    static CapabilityCorpusClusterPolicyProvider unavailable() {
        return new CapabilityCorpusClusterPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<ClusterPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef capabilityRef) {
                return Optional.empty();
            }
        };
    }

    /**
     * Current cluster risk policy.
     *
     * @param scope exact enterprise scope
     * @param capabilityRef exact capability
     * @param policyRef exact policy generation
     * @param minimumSupport minimum recorded members
     * @param minimumDistinctIdentities minimum distinct identities represented
     * @param minimumHoldoutAccepted minimum selected holdout cases
     * @param maximumFalsePositiveBasisPoints maximum conservative false-positive rate
     * @param minimumConfidenceLowerBound minimum Wilson precision lower bound
     * @param maximumUsableHorizon maximum publication lifetime
     * @param publisherPolicy authenticated publisher authorization
     * @param validationPolicy owner restrictions on match and projection paths
     */
    record ClusterPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef policyRef,
            int minimumSupport,
            int minimumDistinctIdentities,
            int minimumHoldoutAccepted,
            int maximumFalsePositiveBasisPoints,
            double minimumConfidenceLowerBound,
            Duration maximumUsableHorizon,
            Predicate<IntegrationRequestContext> publisherPolicy,
            Predicate<CapabilityCorpusClusterValidation> validationPolicy
    ) {
        /** Validates a conservative, bounded policy. */
        public ClusterPolicy {
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            policyRef = ref(
                    policyRef, "CORPUS_CLUSTER_POLICY", "policyRef");
            if (minimumSupport < 2
                    || minimumSupport
                    > CapabilityCorpusClusterValidation.MAXIMUM_MEMBERS
                    || minimumDistinctIdentities < 1
                    || minimumDistinctIdentities > minimumSupport
                    || minimumHoldoutAccepted < 1
                    || maximumFalsePositiveBasisPoints < 0
                    || maximumFalsePositiveBasisPoints > 10_000
                    || !Double.isFinite(minimumConfidenceLowerBound)
                    || minimumConfidenceLowerBound < 0.0d
                    || minimumConfidenceLowerBound > 1.0d) {
                throw new IllegalArgumentException(
                        "cluster policy thresholds are invalid");
            }
            maximumUsableHorizon = Objects.requireNonNull(
                    maximumUsableHorizon, "maximumUsableHorizon");
            if (maximumUsableHorizon.isNegative()
                    || maximumUsableHorizon.isZero()
                    || maximumUsableHorizon.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException(
                        "maximumUsableHorizon must be within 30 days");
            }
            publisherPolicy = Objects.requireNonNull(
                    publisherPolicy, "publisherPolicy");
            validationPolicy = Objects.requireNonNull(
                    validationPolicy, "validationPolicy");
        }

        /**
         * Returns whether the authenticated actor may publish.
         *
         * @param identity authenticated integration identity
         * @return authorization decision
         */
        public boolean mayPublish(IntegrationRequestContext identity) {
            return publisherPolicy.test(
                    Objects.requireNonNull(identity, "identity"));
        }

        /**
         * Returns whether the validation satisfies owner-specific path policy.
         *
         * @param validation exact externally verified validation
         * @return owner policy decision
         */
        public boolean permits(CapabilityCorpusClusterValidation validation) {
            return validationPolicy.test(
                    Objects.requireNonNull(validation, "validation"));
        }
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
