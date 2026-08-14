package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.Objects;

/** Canonical producer and independent verifier for ANEKE Package governance projections. */
public final class DomainCapabilityPackageGovernanceProjectionIntegrity {
    /** Domain separation for ANEKE Package governance signatures. */
    public static final String SIGNATURE_DOMAIN =
            "TOOL_STUDIO_DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_PROJECTION_V1";
    /** Maximum canonical projection bytes. */
    public static final int MAXIMUM_PROJECTION_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public DomainCapabilityPackageGovernanceProjectionIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Signs and content-addresses one complete external projection material. */
    public DomainCapabilityPackageGovernanceProjection seal(
            Material material, VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner authority = Objects.requireNonNull(signer, "signer");
        if (!authority.available()) {
            throw new IllegalArgumentException("governance projection signer is unavailable");
        }
        String materialFingerprint = materialFingerprint(material);
        VisualRunEvidenceSeal seal = authority.seal(materialFingerprint,
                "package-governance-projection:" + material.projectionId()
                        + ":" + material.revision());
        DomainCapabilityPackageGovernanceProjection unsigned = projection(
                material, zeroFingerprint(), seal);
        return projection(material, projectionFingerprint(unsigned), seal);
    }

    /** Verifies content addresses, signing material, and deployment-owned ANEKE trust. */
    public Verification verify(
            DomainCapabilityPackageGovernanceProjection projection,
            PackageGovernanceProjectionTrust trust) {
        if (projection == null) {
            return new Verification(false, "PROJECTION_REQUIRED");
        }
        try {
            Material material = Material.from(projection);
            if (!projection.projectionSeal().materialFingerprint()
                    .equals(materialFingerprint(material))) {
                return new Verification(false, "MATERIAL_FINGERPRINT_INVALID");
            }
            if (!projection.projectionFingerprint().equals(projectionFingerprint(projection))) {
                return new Verification(false, "PROJECTION_FINGERPRINT_INVALID");
            }
            PackageGovernanceProjectionTrust authority = trust == null
                    ? PackageGovernanceProjectionTrust.unavailable() : trust;
            if (!authority.available()) {
                return new Verification(false, "TRUST_UNAVAILABLE");
            }
            if (!authority.verify(projection.projectionSeal(), projection)) {
                return new Verification(false, "SIGNATURE_REJECTED");
            }
            return new Verification(true, "VERIFIED");
        } catch (RuntimeException invalid) {
            return new Verification(false, "PROJECTION_INVALID");
        }
    }

    private String materialFingerprint(Material material) {
        return ProtocolFingerprint.ofBounded(mapper,
                new SigningMaterial(SIGNATURE_DOMAIN,
                        DomainCapabilityPackageGovernanceProjection.SCHEMA_VERSION, material),
                MAXIMUM_PROJECTION_BYTES);
    }

    private String projectionFingerprint(
            DomainCapabilityPackageGovernanceProjection projection) {
        return ProtocolFingerprint.ofBounded(mapper,
                new AddressMaterial(projection.schemaVersion(), "",
                        Material.from(projection), projection.projectionSeal()),
                MAXIMUM_PROJECTION_BYTES);
    }

    private static DomainCapabilityPackageGovernanceProjection projection(
            Material material, String fingerprint, VisualRunEvidenceSeal seal) {
        return new DomainCapabilityPackageGovernanceProjection("", fingerprint,
                material.projectionId(), material.revision(), material.externalGeneration(),
                material.scope(), material.packageSnapshotRef(),
                material.registryIngestBundleRef(), material.evidenceIndexRef(),
                material.registryRecordRef(), material.status(), material.gateDecisionRef(),
                material.sourceCursorFingerprint(), material.producedAt(), material.validFrom(),
                material.expiresAt(), material.issuer(), seal);
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    /** Payload-free projection material signed by ANEKE. */
    public record Material(
            String projectionId,
            long revision,
            long externalGeneration,
            com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot.Scope scope,
            com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef packageSnapshotRef,
            com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef registryIngestBundleRef,
            com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef evidenceIndexRef,
            com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef registryRecordRef,
            DomainCapabilityPackageGovernanceProjection.Status status,
            com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef gateDecisionRef,
            String sourceCursorFingerprint,
            java.time.Instant producedAt,
            java.time.Instant validFrom,
            java.time.Instant expiresAt,
            String issuer
    ) {
        static Material from(DomainCapabilityPackageGovernanceProjection value) {
            return new Material(value.projectionId(), value.revision(),
                    value.externalGeneration(), value.scope(), value.packageSnapshotRef(),
                    value.registryIngestBundleRef(), value.evidenceIndexRef(),
                    value.registryRecordRef(), value.status(), value.gateDecisionRef(),
                    value.sourceCursorFingerprint(), value.producedAt(), value.validFrom(),
                    value.expiresAt(), value.issuer());
        }
    }

    /** Bounded verification result safe to log. */
    public record Verification(boolean verified, String reasonCode) {
    }

    private record SigningMaterial(String domain, String schemaVersion, Material material) {
    }

    private record AddressMaterial(
            String schemaVersion,
            String projectionFingerprint,
            Material material,
            VisualRunEvidenceSeal projectionSeal) {
    }
}
