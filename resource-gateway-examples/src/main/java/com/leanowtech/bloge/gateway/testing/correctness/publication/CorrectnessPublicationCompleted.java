package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Payload-free outbox event emitted atomically with a committed Publication manifest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessPublicationCompleted(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef publicationRef,
        ExactTargetRef target,
        ExactAssetRef definitionRef,
        ExactAssetRef inventoryRef,
        ExactAssetRef scenarioDraftSetRef,
        List<ExactAssetRef> compiledFixtureBundleRefs,
        ExactAssetRef compiledTestSuiteRef,
        String compilationFingerprint,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessPublicationCompleted.v1";

    public CorrectnessPublicationCompleted {
        schemaVersion = version(schemaVersion);
        eventId = required(eventId, "eventId");
        if (scope == null || publicationRef == null || target == null
                || definitionRef == null || inventoryRef == null
                || scenarioDraftSetRef == null || compiledTestSuiteRef == null
                || occurredAt == null) {
            throw new IllegalArgumentException("Publication completion coordinates are required");
        }
        if (!"CORRECTNESS_PUBLICATION".equals(publicationRef.kind())
                || !"TEST_SUITE".equals(compiledTestSuiteRef.kind())) {
            throw new IllegalArgumentException("Publication completion asset kinds are invalid");
        }
        compiledFixtureBundleRefs = compiledFixtureBundleRefs == null ? List.of()
                : compiledFixtureBundleRefs.stream().distinct()
                .sorted(Comparator.comparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision)).toList();
        if (compiledFixtureBundleRefs.isEmpty()
                || compiledFixtureBundleRefs.stream().anyMatch(ref ->
                !"FIXTURE_BUNDLE".equals(ref.kind()))) {
            throw new IllegalArgumentException(
                    "Publication completion requires compiled Fixture Bundle refs");
        }
        compilationFingerprint = fingerprint(compilationFingerprint);
        actorId = required(actorId, "actorId");
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported Publication completion schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value) {
        String normalized = required(value, "compilationFingerprint");
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact compilation fingerprint is required");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
