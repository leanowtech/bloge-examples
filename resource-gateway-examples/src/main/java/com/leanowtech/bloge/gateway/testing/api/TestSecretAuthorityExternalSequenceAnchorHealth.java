package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Aggregate-only Actuator health for the test-secret external non-equivocation quorum. */
public final class TestSecretAuthorityExternalSequenceAnchorHealth implements HealthIndicator {

    private final TestSecretAuthorityExternalSequenceAnchor anchor;

    /**
     * Creates a projection without endpoint, stream, challenge, fingerprint, authority, or key data.
     *
     * @param anchor test-secret external non-equivocation authority
     */
    public TestSecretAuthorityExternalSequenceAnchorHealth(
            TestSecretAuthorityExternalSequenceAnchor anchor) {
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
            ExternalSequenceAnchorReceiptTrustStore.Snapshot trust = anchor.trustSnapshot();
            details.put("trustStatus", trust.status());
            details.put("trustPublicationSequence", trust.publicationSequence());
            details.put("trustAuthorityCount", trust.authorityCount());
            details.put("trustActiveAuthorityCount", trust.activeAuthorityCount());
            details.put("trustLastSuccessfulRefreshAt",
                    trust.lastSuccessfulRefreshAt() == null
                            ? "" : trust.lastSuccessfulRefreshAt().toString());
            details.put("trustRefreshSuccessCount", trust.refreshSuccessCount());
            details.put("trustRefreshFailureCount", trust.refreshFailureCount());
            ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot roots =
                    anchor.bootstrapRootSnapshot();
            details.put("bootstrapRootStatus", roots.status());
            details.put("bootstrapRootHeadSequence", roots.headSequence());
            details.put("bootstrapRootTransitionCount", roots.transitionCount());
            details.put("bootstrapRootAuthorityCount", roots.authorityCount());
            details.put("bootstrapRootActiveAuthorityCount", roots.activeAuthorityCount());
            details.put("bootstrapRootHeadExpiresAt", roots.headExpiresAt() == null
                    ? "" : roots.headExpiresAt().toString());
            details.put("bootstrapRootLastSuccessfulRefreshAt",
                    roots.lastSuccessfulRefreshAt() == null
                            ? "" : roots.lastSuccessfulRefreshAt().toString());
            details.put("bootstrapRootRefreshSuccessCount", roots.refreshSuccessCount());
            details.put("bootstrapRootRefreshFailureCount", roots.refreshFailureCount());
            details.put("transportSecurity", anchor.transportSecurity().asMap());
            return (snapshot.available() ? Health.up() : Health.down())
                    .withDetails(Map.copyOf(details)).build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("status", "UNAVAILABLE").build();
        }
    }
}
