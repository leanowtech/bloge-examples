package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Aggregate-only Actuator health for the external sequence non-equivocation quorum. */
public final class TestSuiteStabilityExternalSequenceAnchorHealth implements HealthIndicator {

    private final TestSuiteStabilityExternalSequenceAnchor anchor;

    /**
     * Creates a projection without endpoint, stream, challenge, fingerprint, authority, or key data.
     *
     * @param anchor external non-equivocation authority
     */
    public TestSuiteStabilityExternalSequenceAnchorHealth(
            TestSuiteStabilityExternalSequenceAnchor anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    /** Returns UP only after the latest anchoring operation achieved a valid quorum. */
    @Override
    public Health health() {
        try {
            TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot = anchor.snapshot();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaVersion", snapshot.schemaVersion());
            details.put("status", snapshot.status());
            details.put("lastSuccessfulAnchorAt", snapshot.lastSuccessfulAnchorAt() == null
                    ? "" : snapshot.lastSuccessfulAnchorAt().toString());
            details.put("successCount", snapshot.successCount());
            details.put("failureCount", snapshot.failureCount());
            details.put("conflictCount", snapshot.conflictCount());
            details.put("authorityCount", snapshot.authorityCount());
            details.put("signatureThreshold", snapshot.signatureThreshold());
            details.put("maximumFaults", snapshot.maximumFaults());
            details.put("independentFailureDomainCount",
                    snapshot.independentFailureDomainCount());
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("status", "UNAVAILABLE").build();
        }
    }
}
