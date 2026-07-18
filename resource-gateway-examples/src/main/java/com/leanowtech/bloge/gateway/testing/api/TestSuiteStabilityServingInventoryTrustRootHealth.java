package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-only Actuator health for managed serving-inventory runtime verification keys.
 *
 * <p>The projection intentionally excludes source URI, ETag, trust-root set id, policy and material
 * fingerprints, authority/key ids, signatures, and public-key material.</p>
 */
public final class TestSuiteStabilityServingInventoryTrustRootHealth implements HealthIndicator {

    private final DynamicTestSuiteStabilityServingInventoryTrustRootAuthority authority;

    /**
     * Creates a key-free health projection over one managed dual key-set authority.
     *
     * @param authority current managed runtime-key source
     */
    public TestSuiteStabilityServingInventoryTrustRootHealth(
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Returns UP only while both current runtime-key thresholds are fresh and usable. */
    @Override
    public Health health() {
        try {
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot snapshot =
                    authority.snapshot();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("status", snapshot.status());
            details.put("sequence", snapshot.sequence());
            details.put("lastSuccessfulRefreshAt",
                    snapshot.lastSuccessfulRefreshAt() == null
                            ? "" : snapshot.lastSuccessfulRefreshAt().toString());
            details.put("refreshSuccessCount", snapshot.refreshSuccessCount());
            details.put("refreshFailureCount", snapshot.refreshFailureCount());
            details.put("lastFailureCode", snapshot.lastFailureCode());
            details.put("refreshIntervalSeconds", snapshot.refreshIntervalSeconds());
            details.put("requestTimeoutMillis", snapshot.requestTimeoutMillis());
            details.put("unknownKeyRefreshIntervalSeconds",
                    snapshot.unknownKeyRefreshIntervalSeconds());
            details.put("maximumSnapshotAgeSeconds", snapshot.maximumSnapshotAgeSeconds());
            details.put("deploymentSignatureThreshold",
                    snapshot.deploymentSignatureThreshold());
            details.put("witnessSignatureThreshold", snapshot.witnessSignatureThreshold());
            details.put("activeDeploymentAuthorityCount",
                    snapshot.activeDeploymentAuthorityCount());
            details.put("activeWitnessAuthorityCount",
                    snapshot.activeWitnessAuthorityCount());
            details.put("durableFloor", snapshot.durableFloor());
            details.put("automaticRefresh", snapshot.automaticRefresh());
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("status", "UNAVAILABLE").build();
        }
    }
}
