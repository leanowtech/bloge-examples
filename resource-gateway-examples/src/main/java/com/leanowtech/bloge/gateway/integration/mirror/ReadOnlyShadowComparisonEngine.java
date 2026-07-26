package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;

/**
 * Exact governed normalized-fact and typed-difference policy executor.
 *
 * <p>Connectors return policy-bound fact fingerprints, while this engine owns the closed mapping
 * from unequal fact sets to dimension-compatible diff types. A production implementation must
 * resolve and verify the exact {@code SHADOW_COMPARISON_POLICY} revision before comparing.</p>
 */
public interface ReadOnlyShadowComparisonEngine {
    /** @return whether the exact comparison policy registry and runtime are ready */
    boolean ready();

    /**
     * Produces canonical typed comparisons under one exact policy.
     *
     * @param comparisonPolicyRef exact normalization and diff policy
     * @param baseline payload-free normalized baseline facts
     * @param candidate payload-free normalized candidate facts
     * @return unique dimension comparisons ordered by dimension name
     */
    List<ReadOnlyShadowComparison.DimensionComparison> compare(
            MirrorArtifactRef comparisonPolicyRef,
            ReadOnlyShadowConnectorObservation baseline,
            ReadOnlyShadowConnectorObservation candidate);

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowComparisonEngine unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowComparisonEngine {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public List<ReadOnlyShadowComparison.DimensionComparison>
        compare(
                MirrorArtifactRef comparisonPolicyRef,
                ReadOnlyShadowConnectorObservation baseline,
                ReadOnlyShadowConnectorObservation candidate) {
            Objects.requireNonNull(
                    comparisonPolicyRef,
                    "comparisonPolicyRef");
            Objects.requireNonNull(
                    baseline, "baseline");
            Objects.requireNonNull(
                    candidate, "candidate");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .NORMALIZATION_POLICY_UNAVAILABLE);
        }
    }
}
