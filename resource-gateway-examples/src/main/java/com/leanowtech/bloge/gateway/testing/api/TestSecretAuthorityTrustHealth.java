package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actuator health boundary for the dynamic test-secret authority trust snapshot.
 *
 * <p>Details use a fixed, payload-free vocabulary. JWKS location, ETag, key identities, public
 * material, secret references and resolved values are deliberately excluded while refresh outage,
 * local expiry and active-key loss remain observable.</p>
 */
public final class TestSecretAuthorityTrustHealth implements HealthIndicator {

    private final DynamicJwksTestSecretAuthorityTrustStore trustStore;

    /**
     * Creates the local dynamic-trust health projection.
     *
     * @param trustStore dynamic test-secret authority trust source
     */
    public TestSecretAuthorityTrustHealth(
            DynamicJwksTestSecretAuthorityTrustStore trustStore) {
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
    }

    /** Returns UP only while one fresh complete snapshot has an active verification key. */
    @Override
    public Health health() {
        try {
            DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot snapshot =
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
            details.put("maximumSnapshotAgeSeconds",
                    snapshot.maximumSnapshotAgeSeconds());
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
