package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Classifies sanitized run payloads and decides whether they may be retained. */
public interface VisualPayloadGovernancePolicy {

    Decision decide(VisualGraphRunRecord record, Instant observedAt);

    Descriptor descriptor();

    record Decision(
            String policyId,
            String policyVersion,
            String classification,
            String requiredClearance,
            Set<String> requiredGroups,
            boolean retain,
            Duration retention
    ) {
        public Decision {
            policyId = normalize(policyId, "default-payload-governance");
            policyVersion = normalize(policyVersion, "1");
            classification = normalize(classification, "RESTRICTED").toUpperCase(Locale.ROOT);
            requiredClearance = normalize(requiredClearance, classification).toUpperCase(Locale.ROOT);
            requiredGroups = requiredGroups == null ? Set.of() : Set.copyOf(requiredGroups);
            retention = retention == null || retention.isNegative() ? Duration.ZERO : retention;
            retain = retain && !retention.isZero();
        }
    }

    record Descriptor(String schemaVersion, String policyId, String policyVersion,
                      String defaultClassification, boolean selectiveRetention,
                      boolean legalHold, boolean signedLifecycle, boolean failClosed) {
        public static final String SCHEMA_VERSION = "bloge.visualPayloadGovernancePolicyDescriptor.v1";

        public Descriptor {
            schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
            policyId = normalize(policyId, "");
            policyVersion = normalize(policyVersion, "");
            defaultClassification = normalize(defaultClassification, "RESTRICTED").toUpperCase(Locale.ROOT);
        }
    }

    static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
