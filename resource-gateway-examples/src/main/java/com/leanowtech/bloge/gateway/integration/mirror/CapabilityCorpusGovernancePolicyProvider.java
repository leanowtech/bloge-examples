package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Operator-owned policy source for quarantine review and corpus publication.
 *
 * <p>Request bodies cannot select policy references, reviewer groups, sample thresholds, or
 * serving horizons. Implementations must return one atomic policy generation rather than merging
 * independently refreshed fragments. Group authorization is evaluated against the authenticated
 * identity and is never inferred from actor-controlled request fields.</p>
 */
public interface CapabilityCorpusGovernancePolicyProvider {
    /**
     * Reports whether governance policy can currently be resolved.
     *
     * @return true only while policy reads are trustworthy
     */
    boolean available();

    /**
     * Resolves the policy governing one exact capability.
     *
     * @param scope complete enterprise scope
     * @param capabilityRef exact capability revision
     * @return atomic policy generation, or empty when no policy governs the capability
     */
    Optional<GovernancePolicy> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef);

    /**
     * Returns a fail-closed provider for unconfigured deployments.
     *
     * @return unavailable policy provider
     */
    static CapabilityCorpusGovernancePolicyProvider unavailable() {
        return new CapabilityCorpusGovernancePolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<GovernancePolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef capabilityRef) {
                return Optional.empty();
            }
        };
    }

    /**
     * Atomic corpus-governance policy generation.
     *
     * @param scope complete governed scope
     * @param capabilityRef exact governed capability
     * @param governancePolicyRef exact candidate and risk policy
     * @param publicationPolicyRef exact serving-publication policy
     * @param quarantineReviewerGroups groups allowed to close quarantine reviews
     * @param publisherGroups groups allowed to publish corpus revisions
     * @param minimumSamples minimum source count for publication eligibility
     * @param maximumSamples maximum source count for publication eligibility
     * @param maximumDuplicateBasisPoints maximum duplicate-request share
     * @param minimumProducerKeys minimum distinct producer-key count
     * @param minimumServingHorizon minimum remaining source usability at publication
     */
    record GovernancePolicy(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef governancePolicyRef,
            MirrorArtifactRef publicationPolicyRef,
            Set<String> quarantineReviewerGroups,
            Set<String> publisherGroups,
            int minimumSamples,
            int maximumSamples,
            int maximumDuplicateBasisPoints,
            int minimumProducerKeys,
            Duration minimumServingHorizon
    ) {
        /** Validates a complete fail-closed policy generation. */
        public GovernancePolicy {
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            governancePolicyRef = ref(
                    governancePolicyRef,
                    "CORPUS_GOVERNANCE_POLICY",
                    "governancePolicyRef");
            publicationPolicyRef = ref(
                    publicationPolicyRef,
                    "CORPUS_PUBLICATION_POLICY",
                    "publicationPolicyRef");
            quarantineReviewerGroups = groups(
                    quarantineReviewerGroups, "quarantineReviewerGroups");
            publisherGroups = groups(publisherGroups, "publisherGroups");
            if (minimumSamples < 1
                    || maximumSamples < minimumSamples
                    || maximumSamples
                    > CapabilityCorpusCandidateRequest.MAXIMUM_SOURCES) {
                throw new IllegalArgumentException(
                        "corpus sample bounds are invalid");
            }
            if (maximumDuplicateBasisPoints < 0
                    || maximumDuplicateBasisPoints > 10_000) {
                throw new IllegalArgumentException(
                        "maximumDuplicateBasisPoints is invalid");
            }
            if (minimumProducerKeys < 1
                    || minimumProducerKeys > maximumSamples) {
                throw new IllegalArgumentException(
                        "minimumProducerKeys is invalid");
            }
            minimumServingHorizon = Objects.requireNonNull(
                    minimumServingHorizon, "minimumServingHorizon");
            if (minimumServingHorizon.isNegative()
                    || minimumServingHorizon.compareTo(Duration.ofDays(3650)) > 0) {
                throw new IllegalArgumentException(
                        "minimumServingHorizon is invalid");
            }
        }

        /**
         * Checks whether the authenticated actor may review quarantine.
         *
         * @param identity authenticated workload identity
         * @return true when at least one governed reviewer group matches
         */
        public boolean mayReview(IntegrationRequestContext identity) {
            return authorized(identity, quarantineReviewerGroups);
        }

        /**
         * Checks whether the authenticated actor may publish a serving corpus.
         *
         * @param identity authenticated workload identity
         * @return true when at least one governed publisher group matches
         */
        public boolean mayPublish(IntegrationRequestContext identity) {
            return authorized(identity, publisherGroups);
        }
    }

    private static boolean authorized(
            IntegrationRequestContext identity, Set<String> allowed) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        return exact.groups().stream().anyMatch(allowed::contains);
    }

    private static Set<String> groups(Set<String> values, String field) {
        if (values == null || values.isEmpty() || values.size() > 64
                || values.stream().anyMatch(value ->
                value == null
                        || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,127}"))) {
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
