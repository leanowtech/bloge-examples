package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/** Immutable payload reference and policy decision sealed into the run evidence. */
public record VisualPayloadRetentionDescriptor(
        String schemaVersion,
        String policyId,
        String policyVersion,
        String classification,
        String requiredClearance,
        Set<String> requiredGroups,
        String disposition,
        String payloadRef,
        String payloadFingerprint,
        Instant retainedAt,
        Instant expiresAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualPayloadRetentionDescriptor.v1";
    public static final String RETAINED = "RETAINED";
    public static final String NOT_RETAINED = "NOT_RETAINED";
    public static final String LEGACY_INLINE = "LEGACY_INLINE";

    public VisualPayloadRetentionDescriptor {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        policyId = normalize(policyId, "");
        policyVersion = normalize(policyVersion, "");
        classification = normalize(classification, "UNKNOWN").toUpperCase(Locale.ROOT);
        requiredClearance = normalize(requiredClearance, "RESTRICTED").toUpperCase(Locale.ROOT);
        requiredGroups = requiredGroups == null ? Set.of() : Set.copyOf(requiredGroups);
        disposition = normalize(disposition, NOT_RETAINED).toUpperCase(Locale.ROOT);
        payloadRef = normalize(payloadRef, "");
        payloadFingerprint = normalize(payloadFingerprint, "");
        retainedAt = retainedAt == null ? Instant.EPOCH : retainedAt;
        expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
    }

    public boolean retained() {
        return RETAINED.equals(disposition) && !payloadRef.isBlank() && !payloadFingerprint.isBlank();
    }

    public static VisualPayloadRetentionDescriptor legacyInline() {
        return new VisualPayloadRetentionDescriptor("", "legacy-inline", "0", "UNKNOWN", "RESTRICTED",
                Set.of(), LEGACY_INLINE, "", "", Instant.EPOCH, Instant.EPOCH);
    }

    public static VisualPayloadRetentionDescriptor notRetained(String policyId,
                                                               String policyVersion,
                                                               String classification,
                                                               String requiredClearance,
                                                               Set<String> requiredGroups,
                                                               Instant observedAt) {
        return new VisualPayloadRetentionDescriptor("", policyId, policyVersion, classification,
                requiredClearance, requiredGroups, NOT_RETAINED, "", "", observedAt, observedAt);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
