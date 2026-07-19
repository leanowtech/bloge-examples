package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Aggregate-only Actuator health for external immutable observation-archive writes. */
public final class TestSuiteStabilityObservationExternalArchiveHealth
        implements HealthIndicator {

    private final TestSuiteStabilityObservationExternalArchiveAuthority authority;

    /**
     * Creates a projection without endpoint, authority, key, object, or fingerprint identities.
     *
     * @param authority external immutable archive authority
     */
    public TestSuiteStabilityObservationExternalArchiveHealth(
            TestSuiteStabilityObservationExternalArchiveAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Returns UP only after the latest archive operation established the configured copy set. */
    @Override
    public Health health() {
        try {
            TestSuiteStabilityObservationExternalArchiveAuthority.Snapshot snapshot =
                    authority.snapshot();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("status", snapshot.status());
            details.put("lastSuccessfulArchiveAt",
                    snapshot.lastSuccessfulArchiveAt() == null
                            ? "" : snapshot.lastSuccessfulArchiveAt().toString());
            details.put("successCount", snapshot.successCount());
            details.put("failureCount", snapshot.failureCount());
            details.put("conflictCount", snapshot.conflictCount());
            details.put("authorityCount", snapshot.authorityCount());
            details.put("requiredCopies", snapshot.requiredCopies());
            details.put("independentFailureDomainCount",
                    snapshot.independentFailureDomainCount());
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("status", "UNAVAILABLE").build();
        }
    }
}
