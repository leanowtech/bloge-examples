package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-only Actuator health for dynamic provider inventory and fleet convergence.
 *
 * <p>Details intentionally omit endpoint, ETag, replica identities, provider/deployment ids,
 * fingerprints, signatures, authority ids, key ids, and key material.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptProviderInventoryHealth
        implements HealthIndicator {

    private final DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort;

    /**
     * Creates the aggregate refresh and convergence projection.
     *
     * @param authority dynamic witnessed provider-inventory authority
     * @param cohort durable signed-replica convergence gate
     */
    public TestSuiteStabilityPhysicalAttemptProviderInventoryHealth(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority,
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.cohort = Objects.requireNonNull(cohort, "cohort");
    }

    /** Returns UP only for one fresh ACTIVE publication and exact converged replica cohort. */
    @Override
    public Health health() {
        try {
            var first = authority.observation();
            var snapshot = authority.snapshot();
            var convergence = cohort.observation();
            var last = authority.observation();
            boolean exact = first.equals(last) && snapshot.available()
                    && convergence.available()
                    && snapshot.sequence() == first.sourceSequence()
                    && convergence.inventorySourceSequence() == first.sourceSequence()
                    && convergence.inventoryGenerationFingerprint().equals(
                    first.sourceGenerationFingerprint());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("sourceType",
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .SOURCE_TYPE);
            details.put("refreshState", snapshot.refreshState());
            details.put("publicationState", snapshot.publicationState());
            details.put("publicationSequence", snapshot.sequence());
            details.put("lastSuccessfulRefreshAt",
                    snapshot.lastSuccessfulRefreshAt() == null
                            ? "" : snapshot.lastSuccessfulRefreshAt().toString());
            details.put("refreshSuccessCount", snapshot.refreshSuccessCount());
            details.put("refreshFailureCount", snapshot.refreshFailureCount());
            details.put("lastFailureCode", snapshot.lastFailureCode());
            details.put("refreshIntervalSeconds", snapshot.refreshIntervalSeconds());
            details.put("maximumSnapshotAgeSeconds",
                    snapshot.maximumSnapshotAgeSeconds());
            details.put("witnessSignatureThreshold",
                    snapshot.witnessSignatureThreshold());
            details.put("durablePublicationFloor", snapshot.durablePublicationFloor());
            details.put("cohortStatus", convergence.status());
            details.put("expectedReplicaCount", convergence.expectedReplicas());
            details.put("readyReplicaCount", convergence.readyReplicas());
            details.put("distinctInventoryGenerations",
                    convergence.distinctInventoryGenerations());
            return (exact ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("sourceType",
                            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                    .SOURCE_TYPE)
                    .withDetail("refreshState", "UNAVAILABLE")
                    .build();
        }
    }
}
