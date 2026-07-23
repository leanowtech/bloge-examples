package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRetryPolicyProviderTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            CapabilityObservationTestFixtures.scope("org-a");
    private static final MirrorArtifactRef CAPABILITY =
            CapabilityObservationTestFixtures.ref(
                    "CAPABILITY", "support-capability", 1, '1');
    private static final MirrorArtifactRef POLICY =
            CapabilityObservationTestFixtures.ref(
                    "RETRY_POLICY", "support-retry-policy", 1, '2');

    @Test
    void everyConfiguredRetryDimensionMustMatch() {
        CapabilityRetryPolicyProvider.RetryPolicy policy = policy(
                Set.of("TRANSIENT_UPSTREAM"),
                Set.of("UPSTREAM_TIMEOUT"));

        assertThat(policy.permits(error(
                "TRANSIENT_UPSTREAM", "UPSTREAM_TIMEOUT", true))).isTrue();
        assertThat(policy.permits(error(
                "TRANSIENT_UPSTREAM", "RATE_LIMITED", true))).isFalse();
        assertThat(policy.permits(error(
                "PERMANENT_UPSTREAM", "UPSTREAM_TIMEOUT", true))).isFalse();
        assertThat(policy.permits(error(
                "TRANSIENT_UPSTREAM", "UPSTREAM_TIMEOUT", false))).isFalse();
    }

    @Test
    void emptyRetryDimensionLeavesOnlyConfiguredDimensionConstrained() {
        assertThat(policy(
                Set.of("TRANSIENT_UPSTREAM"),
                Set.of()).permits(error(
                "TRANSIENT_UPSTREAM", "ANY_CODE", true))).isTrue();
        assertThat(policy(
                Set.of(),
                Set.of("UPSTREAM_TIMEOUT")).permits(error(
                "ANY_CLASS", "UPSTREAM_TIMEOUT", true))).isTrue();
    }

    private static CapabilityRetryPolicyProvider.RetryPolicy policy(
            Set<String> errorClasses,
            Set<String> errorCodes) {
        return new CapabilityRetryPolicyProvider.RetryPolicy(
                SCOPE,
                CAPABILITY,
                POLICY,
                3,
                errorClasses,
                errorCodes);
    }

    private static CapabilityObservationEnvelope.NormalizedError error(
            String errorClass,
            String errorCode,
            boolean retryable) {
        return new CapabilityObservationEnvelope.NormalizedError(
                errorClass,
                errorCode,
                retryable,
                "sha256:" + "3".repeat(64));
    }
}
