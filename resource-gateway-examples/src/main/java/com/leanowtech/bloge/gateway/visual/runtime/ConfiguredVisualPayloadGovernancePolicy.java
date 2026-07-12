package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative configurable policy; enterprises may replace this bean with their policy engine adapter. */
public final class ConfiguredVisualPayloadGovernancePolicy implements VisualPayloadGovernancePolicy {

    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final String policyId;
    private final String policyVersion;
    private final String classification;
    private final Set<String> requiredGroups;
    private final Map<String, Duration> retentionByClassification;

    public ConfiguredVisualPayloadGovernancePolicy(String policyId,
                                                   String policyVersion,
                                                   String classification,
                                                   Set<String> requiredGroups,
                                                   Map<String, Duration> retentionByClassification) {
        this.policyId = VisualPayloadGovernancePolicy.normalize(policyId, "resource-gateway-default");
        this.policyVersion = VisualPayloadGovernancePolicy.normalize(policyVersion, "1");
        this.classification = normalizeClassification(classification);
        this.requiredGroups = requiredGroups == null ? Set.of() : Set.copyOf(requiredGroups);
        this.retentionByClassification = normalizeRetention(retentionByClassification);
    }

    @Override
    public Decision decide(VisualGraphRunRecord record, Instant observedAt) {
        Duration retention = retentionByClassification.getOrDefault(classification, Duration.ZERO);
        return new Decision(policyId, policyVersion, classification, classification, requiredGroups,
                !retention.isZero(), retention);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("", policyId, policyVersion, classification, true, true, true, true);
    }

    private static String normalizeClassification(String value) {
        String normalized = VisualPayloadGovernancePolicy.normalize(value, "RESTRICTED")
                .toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported payload classification: " + value);
        }
        return normalized;
    }

    private static Map<String, Duration> normalizeRetention(Map<String, Duration> values) {
        Map<String, Duration> source = values == null ? Map.of() : values;
        java.util.LinkedHashMap<String, Duration> normalized = new java.util.LinkedHashMap<>();
        for (String classification : CLASSIFICATIONS) {
            Duration duration = source.getOrDefault(classification, Duration.ZERO);
            if (duration == null || duration.isNegative() || duration.compareTo(Duration.ofDays(3650)) > 0) {
                throw new IllegalArgumentException("Payload retention must be between 0 and 3650 days");
            }
            normalized.put(classification, duration);
        }
        return Map.copyOf(normalized);
    }
}
