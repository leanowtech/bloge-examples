package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;

/** Payload-free catalog metadata for a protected Fixture material revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FixtureAssetDescriptor(
        String schemaVersion,
        String fixtureAssetId,
        long revision,
        EnterpriseScope scope,
        String name,
        FixtureSource source,
        ExactAssetRef materialRef,
        ExactSchemaRef schemaRef,
        String variantKey,
        FixtureLifecycle lifecycle,
        String classification,
        PrincipalRef owner,
        RedactionDescriptor redaction,
        RetentionDescriptor retention,
        QualityProfile quality,
        List<String> tags,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.fixtureAssetDescriptor.v1";

    public enum SourceKind {
        SAMPLE, SCHEMA_GENERATED, SCENARIO, INCIDENT_CAPTURE, REPLAY_DERIVATION,
        OPERATOR_TEST_CASE, FUNCTION_TEST_CASE, MIGRATED
    }

    public enum FixtureLifecycle { DRAFT, PROPOSED, APPROVED, ACTIVE, STALE, REVOKED, EXPIRED }

    public FixtureAssetDescriptor {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
        revision = mutableRevision(revision);
        scope = required(scope, "scope");
        name = required(name, "name");
        source = required(source, "source");
        materialRef = required(materialRef, "materialRef");
        schemaRef = required(schemaRef, "schemaRef");
        variantKey = required(variantKey, "variantKey");
        lifecycle = lifecycle == null ? FixtureLifecycle.DRAFT : lifecycle;
        classification = required(classification, "classification").toUpperCase(Locale.ROOT);
        owner = required(owner, "owner");
        redaction = required(redaction, "redaction");
        retention = required(retention, "retention");
        quality = required(quality, "quality");
        tags = sortedStrings(tags);
        metadata = required(metadata, "metadata");
        if ((lifecycle == FixtureLifecycle.APPROVED || lifecycle == FixtureLifecycle.ACTIVE)
                && (!redaction.reviewed() || retention.expiresAt() == null)) {
            throw new IllegalArgumentException(
                    "Approved Fixture requires reviewed redaction and bounded retention");
        }
    }

    public FixtureAssetDescriptor persistedAs(long persistedRevision, AuditMetadata persistedMetadata) {
        return new FixtureAssetDescriptor(
                schemaVersion, fixtureAssetId, persistedRevision, scope, name, source,
                materialRef, schemaRef, variantKey, lifecycle, classification, owner,
                redaction, retention, quality, tags, persistedMetadata);
    }

    public FixtureAssetDescriptor withLifecycle(FixtureLifecycle nextLifecycle) {
        return new FixtureAssetDescriptor(
                schemaVersion, fixtureAssetId, revision, scope, name, source,
                materialRef, schemaRef, variantKey, nextLifecycle, classification, owner,
                redaction, retention, quality, tags, metadata);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixtureSource(SourceKind kind, ExactAssetRef sourceRef) {
        public FixtureSource {
            kind = required(kind, "kind");
            if (kind != SourceKind.SAMPLE && kind != SourceKind.SCHEMA_GENERATED
                    && sourceRef == null) {
                throw new IllegalArgumentException("Fixture source requires an exact sourceRef");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RedactionDescriptor(
            String profileVersion,
            List<String> redactedPaths,
            boolean reviewed
    ) {
        public RedactionDescriptor {
            profileVersion = required(profileVersion, "profileVersion");
            redactedPaths = sortedStrings(redactedPaths);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetentionDescriptor(
            String policyVersion,
            int retentionDays,
            Instant expiresAt
    ) {
        public RetentionDescriptor {
            policyVersion = required(policyVersion, "policyVersion");
            if (retentionDays < 1 || expiresAt == null) {
                throw new IllegalArgumentException(
                        "Fixture retention requires positive days and expiresAt");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QualityProfile(
            boolean schemaValid,
            boolean redactionVerified,
            int duplicateCandidateCount,
            int usageCount
    ) {
        public QualityProfile {
            if (duplicateCandidateCount < 0 || usageCount < 0) {
                throw new IllegalArgumentException("Fixture quality counts must not be negative");
            }
        }
    }
}
