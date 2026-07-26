package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Payload-free result returned across the isolated connector boundary.
 *
 * <p>Raw request/response values remain inside the connector trust domain. Only an independently
 * signed source observation, exact normalization policy, canonical fact fingerprints, and
 * measured zero-write counters may cross this boundary.</p>
 *
 * @param source independently signed source artifact coordinates
 * @param comparisonPolicyRef exact policy used to normalize facts
 * @param normalizedFactFingerprints canonical dimension-to-fact-set fingerprints
 * @param writeCredentialExposed whether a write-capable credential reached the source
 * @param writeAttemptCount observed external write attempts
 */
public record ReadOnlyShadowConnectorObservation(
        ReadOnlyShadowComparison.SourceObservation source,
        MirrorArtifactRef comparisonPolicyRef,
        Map<DomainFidelityProfile.Dimension, String>
                normalizedFactFingerprints,
        boolean writeCredentialExposed,
        long writeAttemptCount
) {
    /** Validates bounded payload-free facts while preserving measured write violations. */
    public ReadOnlyShadowConnectorObservation {
        source = Objects.requireNonNull(
                source, "source");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        Map<DomainFidelityProfile.Dimension, String>
                supplied = normalizedFactFingerprints == null
                ? Map.of() : normalizedFactFingerprints;
        LinkedHashMap<DomainFidelityProfile.Dimension, String>
                canonical = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry -> canonical.put(
                        Objects.requireNonNull(
                                entry.getKey(), "dimension"),
                        fingerprint(
                                entry.getValue(),
                                "normalizedFactFingerprint")));
        normalizedFactFingerprints =
                Collections.unmodifiableMap(canonical);
        if (normalizedFactFingerprints.isEmpty()
                || normalizedFactFingerprints.size() > 16
                || writeAttemptCount < 0
                || writeAttemptCount > 1_000_000_000L) {
            throw new IllegalArgumentException(
                    "read-only Shadow connector observation is invalid");
        }
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
