package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Immutable compilation fact for one exact Domain Capability Package authoring revision.
 *
 * <p>The snapshot is suitable for ANEKE ingestion but does not carry ANEKE's publication or
 * certification decision. It references, rather than copies, execution and evidence artifacts.</p>
 */
public record DomainCapabilityPackageSnapshot(
        String schemaVersion,
        String packageId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        long sourceDraftRevision,
        String sourceDraftFingerprint,
        DomainCapabilityPackageDraft.BusinessDefinition businessDefinition,
        MirrorArtifactRef packageContractRef,
        MirrorArtifactRef capabilityClosureRef,
        List<MirrorArtifactRef> mirrorPlanRefs,
        MirrorArtifactRef businessAssetLinkClosureRef,
        MirrorArtifactRef readinessReportRef,
        List<MirrorArtifactRef> dependencyManifest,
        List<MirrorArtifactRef> evidenceRefs,
        String compilerVersion,
        MirrorArtifactRef policyGenerationRef,
        ArtifactProvenance provenance,
        Instant createdAt
) {
    /** Current immutable Package snapshot version. */
    public static final String SCHEMA_VERSION = "resourceGateway.domainCapabilityPackageSnapshot.v1";

    /** Enforces exact immutable references, deterministic manifests, and source lineage. */
    public DomainCapabilityPackageSnapshot {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        packageId = BusinessMirrorProtocolSupport.identifier(packageId, "packageId");
        if (revision < 1 || sourceDraftRevision < 1) {
            throw new IllegalArgumentException("snapshot and source draft revisions must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        sourceDraftFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                sourceDraftFingerprint, "sourceDraftFingerprint");
        businessDefinition = java.util.Objects.requireNonNull(businessDefinition, "businessDefinition");
        packageContractRef = BusinessMirrorProtocolSupport.exactRef(
                packageContractRef, "CONTRACT", "packageContractRef");
        capabilityClosureRef = BusinessMirrorProtocolSupport.exactRef(
                capabilityClosureRef, "CAPABILITY_CLOSURE", "capabilityClosureRef");
        mirrorPlanRefs = BusinessMirrorProtocolSupport.exactRefs(
                mirrorPlanRefs, Set.of("MIRROR_PLAN"), "mirrorPlanRefs");
        if (mirrorPlanRefs.isEmpty()) {
            throw new IllegalArgumentException("snapshot requires at least one MirrorPlan");
        }
        businessAssetLinkClosureRef = BusinessMirrorProtocolSupport.exactRef(
                businessAssetLinkClosureRef, "BUSINESS_ASSET_LINK_CLOSURE",
                "businessAssetLinkClosureRef");
        readinessReportRef = BusinessMirrorProtocolSupport.exactRef(
                readinessReportRef, "PACKAGE_READINESS_REPORT", "readinessReportRef");
        dependencyManifest = BusinessMirrorProtocolSupport.immutableRefs(
                dependencyManifest, "dependencyManifest");
        if (dependencyManifest.isEmpty()) {
            throw new IllegalArgumentException("snapshot dependency manifest must not be empty");
        }
        evidenceRefs = BusinessMirrorProtocolSupport.immutableRefs(evidenceRefs, "evidenceRefs");
        compilerVersion = BusinessMirrorProtocolSupport.identifier(compilerVersion, "compilerVersion");
        policyGenerationRef = BusinessMirrorProtocolSupport.exactRef(
                policyGenerationRef, "PACKAGE_COMPILATION_POLICY", "policyGenerationRef");
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        if (!scope.tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException("snapshot provenance tenant must match package scope");
        }
        requireCompleteBusinessDefinition(businessDefinition);
    }

    /** @return exact content-addressed Package snapshot reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("Package snapshot is not content-addressed");
        }
        return new MirrorArtifactRef("DOMAIN_CAPABILITY_PACKAGE", packageId, revision, fingerprint);
    }

    /** @return identical snapshot with a replacement canonical fingerprint */
    public DomainCapabilityPackageSnapshot withFingerprint(String value) {
        return new DomainCapabilityPackageSnapshot(schemaVersion, packageId, revision, value, scope,
                sourceDraftRevision, sourceDraftFingerprint, businessDefinition, packageContractRef,
                capabilityClosureRef, mirrorPlanRefs, businessAssetLinkClosureRef, readinessReportRef,
                dependencyManifest, evidenceRefs, compilerVersion, policyGenerationRef, provenance,
                createdAt);
    }

    /** @return content-addressed snapshot */
    public DomainCapabilityPackageSnapshot seal(ObjectMapper mapper) {
        return withFingerprint(ProtocolFingerprint.ofBounded(
                java.util.Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                BusinessMirrorProtocolSupport.MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the snapshot content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Domain capability package snapshot fingerprint mismatch");
        }
    }

    private static void requireCompleteBusinessDefinition(
            DomainCapabilityPackageDraft.BusinessDefinition definition) {
        if (definition.domainId().isBlank()
                || definition.problemTaxonomyRef() == null
                || definition.problemCode().isBlank()
                || definition.businessGoal().isBlank()
                || definition.expectedOutcome().isBlank()
                || definition.accountableOwner().isBlank()) {
            throw new IllegalArgumentException("snapshot requires a complete business definition");
        }
    }
}
