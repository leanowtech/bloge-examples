package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actuator health boundary for the dynamic suite-stability authority trust snapshot.
 *
 * <p>Details are fixed-vocabulary and payload-free. They intentionally omit JWKS URI, ETag, key
 * ids and public material while retaining enough state to alert on refresh outage or expiry.</p>
 */
public final class TestSuiteStabilityAuthorityTrustHealth implements HealthIndicator {

    private final DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore;

    /**
     * Creates the local health projection.
     *
     * @param trustStore dynamic authority trust source
     */
    public TestSuiteStabilityAuthorityTrustHealth(
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    /** Returns UP only while one fresh local snapshot has an active verification key. */
    @Override
    public Health health() {
        try {
            DynamicJwksTestSuiteStabilityAuthorityTrustStore.RefreshSnapshot snapshot =
                    trustStore.snapshot();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("providerType", "DYNAMIC_JWKS_ED25519");
            details.put("refreshState", snapshot.refreshState());
            details.put("trustedKeyCount", snapshot.trustedKeyCount());
            details.put("activeKeyCount", snapshot.activeKeyCount());
            details.put("lastSuccessfulRefreshAt",
                    snapshot.lastSuccessfulRefreshAt() == null
                            ? "" : snapshot.lastSuccessfulRefreshAt().toString());
            details.put("refreshSuccessCount", snapshot.refreshSuccessCount());
            details.put("refreshFailureCount", snapshot.refreshFailureCount());
            details.put("lastFailureCode", snapshot.lastFailureCode());
            details.put("refreshIntervalSeconds", snapshot.refreshIntervalSeconds());
            details.put("maximumSnapshotAgeSeconds", snapshot.maximumSnapshotAgeSeconds());
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("providerType", "DYNAMIC_JWKS_ED25519")
                    .withDetail("refreshState", "UNAVAILABLE")
                    .build();
        }
    }
}
