package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Versioned wire contracts for payload-bearing authoring fixtures.
 */
public final class AuthoringFixtureProtocol {

    private AuthoringFixtureProtocol() {
    }

    /** Origin of the explicitly persisted payload. */
    public enum SourceKind {
        SAMPLE,
        OPERATOR_TEST_CASE,
        FUNCTION_TEST_CASE
    }

    /** Exact progressive-library asset bound to the fixture. */
    public enum AssetKind {
        OPERATOR,
        FUNCTION
    }

    /**
     * Saves one immutable revision after redaction and encryption.
     */
    public record SaveRequest(
            String schemaVersion,
            String fixtureId,
            long expectedFixtureRevision,
            SourceKind sourceKind,
            AssetKind assetKind,
            String assetRef,
            String classification,
            int retentionDays,
            List<String> redactionPaths,
            Object payload
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringFixtureSaveRequest.v1";

        public SaveRequest {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            fixtureId = trimmed(fixtureId);
            assetRef = trimmed(assetRef);
            classification = normalized(classification, "INTERNAL")
                    .toUpperCase(Locale.ROOT);
            redactionPaths = redactionPaths == null
                    ? List.of() : redactionPaths.stream()
                    .map(AuthoringFixtureProtocol::trimmed)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            payload = ProtocolJsonValue.freeze(payload);
        }
    }

    /**
     * Payload-free immutable storage receipt and lineage descriptor.
     */
    public record FixtureReceipt(
            String schemaVersion,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            String fixtureId,
            long revision,
            SourceKind sourceKind,
            AssetKind assetKind,
            String assetRef,
            String draftId,
            long authoringRevision,
            String authoringFingerprint,
            String canonicalFingerprint,
            String artifactFingerprint,
            String payloadFingerprint,
            String classification,
            String retentionPolicyVersion,
            Instant expiresAt,
            String redactionProfileVersion,
            List<String> redactedPaths,
            Instant createdAt,
            String createdBy,
            boolean payloadPersisted,
            boolean payloadReturned
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringFixtureReceipt.v1";

        public FixtureReceipt {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            tenantId = trimmed(tenantId);
            organizationId = trimmed(organizationId);
            projectId = trimmed(projectId);
            environmentId = trimmed(environmentId);
            region = trimmed(region);
            fixtureId = trimmed(fixtureId);
            assetRef = trimmed(assetRef);
            draftId = trimmed(draftId);
            authoringFingerprint = trimmed(authoringFingerprint);
            canonicalFingerprint = trimmed(canonicalFingerprint);
            artifactFingerprint = trimmed(artifactFingerprint);
            payloadFingerprint = trimmed(payloadFingerprint);
            classification = normalized(classification, "INTERNAL")
                    .toUpperCase(Locale.ROOT);
            retentionPolicyVersion = trimmed(retentionPolicyVersion);
            redactionProfileVersion = trimmed(redactionProfileVersion);
            redactedPaths = redactedPaths == null
                    ? List.of() : List.copyOf(redactedPaths);
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            createdBy = trimmed(createdBy);
        }
    }

    /**
     * Authorized exact-revision read; only this contract can carry decrypted fixture material.
     */
    public record FixtureMaterial(
            String schemaVersion,
            FixtureReceipt fixture,
            Object payload,
            boolean payloadReturned
    ) {
        public static final String SCHEMA_VERSION =
                "bloge.visualAuthoringFixtureMaterial.v1";

        public FixtureMaterial {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            payload = ProtocolJsonValue.freeze(payload);
        }
    }

    private static String normalized(String value, String fallback) {
        String normalized = trimmed(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
