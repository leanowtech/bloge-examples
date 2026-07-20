package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-only Actuator health for dynamic test-secret serving-inventory refresh.
 *
 * <p>Details deliberately omit the endpoint, ETag, fleet members, authority identities,
 * fingerprints, signatures, keys and test-secret material.</p>
 */
public final class TestSecretAuthorityServingInventoryHealth implements HealthIndicator {

    private final DynamicTestSecretAuthorityServingInventoryAuthority authority;

    /**
     * Creates the local refresh health projection.
     *
     * @param authority dynamic witnessed test-secret serving-inventory authority
     */
    public TestSecretAuthorityServingInventoryHealth(
            DynamicTestSecretAuthorityServingInventoryAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Returns UP only while a fresh witnessed publication is active. */
    @Override
    public Health health() {
        try {
            DynamicTestSecretAuthorityServingInventoryAuthority.Snapshot snapshot =
                    authority.snapshot();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("providerType",
                    DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE);
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
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("providerType",
                            DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE)
                    .withDetail("refreshState", "UNAVAILABLE")
                    .build();
        }
    }
}
