package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Independent Package registry-ingest and ANEKE governance-projection verification.
 *
 * <p>This API has no Resource Gateway server or Spring dependency. A consumer must supply its
 * own ANEKE trust decision; successful canonical verification never grants registry or publish
 * authority.</p>
 */
public final class PackageGovernanceProtocol {
    /** Tool Studio schema resources packaged in the test-kit JAR. */
    public static final String SCHEMA_RESOURCE_ROOT = "/schemas/tool-studio-resource-gateway/";
    /** Package registry-ingest bundle v1. */
    public static final String PACKAGE_REGISTRY_INGEST_BUNDLE_V1 =
            "toolStudio.resourceGateway.packageRegistryIngestBundle.v1";
    /** Signed ANEKE Package governance projection v1. */
    public static final String PACKAGE_GOVERNANCE_PROJECTION_V1 =
            "toolStudio.domainCapabilityPackageGovernanceProjection.v1";
    /** Joined Resource Gateway/ANEKE governance view v1. */
    public static final String PACKAGE_GOVERNANCE_VIEW_V1 =
            "toolStudio.domainCapabilityPackageGovernanceView.v1";
    /** Projection-ingest receipt v1. */
    public static final String PACKAGE_GOVERNANCE_RECEIPT_V1 =
            "toolStudio.packageGovernanceProjectionReceipt.v1";
    /** Signature domain separating governance projections from every other evidence type. */
    public static final String PROJECTION_SIGNATURE_DOMAIN =
            "TOOL_STUDIO_DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_PROJECTION_V1";
    /** Fixed registry-ingest fixture packaged for consumer conformance tests. */
    public static final String PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-registry-ingest-bundle-v1.fixture.json";
    /** Fixed signed projection fixture packaged for consumer conformance tests. */
    public static final String PACKAGE_GOVERNANCE_PROJECTION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "domain-capability-package-governance-projection-v1.fixture.json";

    private static final String BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-registry-ingest-bundle-v1.schema.json";
    private static final String PROJECTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "domain-capability-package-governance-projection-v1.schema.json";
    private static final String VIEW_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "domain-capability-package-governance-view-v1.schema.json";
    private static final String RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "package-governance-projection-receipt-v1.schema.json";
    private static final Duration MAXIMUM_PROJECTION_LIFETIME = Duration.ofDays(7);
    private static final String TOO_LARGE =
            "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_GOVERNANCE_VALUE_TOO_LARGE";
    private static final String CANONICALIZATION_FAILED =
            "RG.BUSINESS_MIRROR.CLIENT.PACKAGE_GOVERNANCE_CANONICALIZATION_FAILED";

    private PackageGovernanceProtocol() {
    }

    /**
     * Verifies one complete Resource Gateway immutable Package/evidence export.
     *
     * @param bundle untrusted registry-ingest bundle
     * @return payload-free verified bundle coordinates
     * @throws IllegalArgumentException when Schema, address, time, or closure verification fails
     */
    public static VerifiedRegistryBundle verifyRegistryIngestBundle(JsonNode bundle) {
        BusinessMirrorSchemaValidator.require(bundle, BUNDLE_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.REGISTRY_INGEST_BUNDLE_INVALID");
        BusinessMirrorCompilationVerifier.VerifiedPackageSnapshot snapshot =
                BusinessMirrorCompilationVerifier.verifyPackageSnapshot(
                        bundle.path("packageSnapshot"));
        BusinessMirrorCompilationVerifier.VerifiedReadiness readiness =
                BusinessMirrorCompilationVerifier.verifyPackageReadinessReport(
                        bundle.path("readinessReport"));
        BusinessMirrorCompilationVerifier.VerifiedLinkClosure closure =
                BusinessMirrorCompilationVerifier.verifyBusinessAssetLinkClosure(
                        bundle.path("businessAssetLinkClosure"));
        BusinessMirrorEvidenceVerifier.VerifiedEvidenceIndex evidence =
                BusinessMirrorEvidenceVerifier.verifyIndex(bundle.path("evidenceIndex"));

        ObjectNode material = object(bundle, "REGISTRY_INGEST_BUNDLE_INVALID");
        material.put("bundleFingerprint", "");
        require(bundle.path("bundleFingerprint").asText().equals(fingerprint(material)),
                "REGISTRY_INGEST_BUNDLE_FINGERPRINT_MISMATCH");

        JsonNode scope = bundle.path("scope");
        String packageId = snapshot.packageId();
        long revision = bundle.path("revision").asLong();
        boolean closes = scope.equals(bundle.path("packageSnapshot").path("scope"))
                && scope.equals(bundle.path("readinessReport").path("scope"))
                && scope.equals(bundle.path("businessAssetLinkClosure").path("scope"))
                && scope.equals(bundle.path("evidenceIndex").path("scope"))
                && packageId.equals(readiness.packageId())
                && packageId.equals(closure.packageId())
                && packageId.equals(bundle.path("evidenceIndex").path("packageId").asText())
                && revision == snapshot.revision()
                && revision == readiness.revision()
                && revision == closure.revision()
                && revision == bundle.path("evidenceIndex")
                .path("compilationRevision").asLong()
                && bundle.path("dependencyManifest")
                .equals(bundle.path("packageSnapshot").path("dependencyManifest"))
                && artifactRefMatches(bundle.path("packageSnapshot").path("readinessReportRef"),
                "PACKAGE_READINESS_REPORT", readiness.reportId(), readiness.revision(),
                readiness.fingerprint())
                && artifactRefMatches(bundle.path("packageSnapshot")
                .path("businessAssetLinkClosureRef"), "BUSINESS_ASSET_LINK_CLOSURE",
                closure.closureId(), closure.revision(), closure.fingerprint())
                && evidenceSourceMatches(bundle.path("evidenceIndex")
                .path("packageSnapshotSource"), "DOMAIN_CAPABILITY_PACKAGE", packageId,
                snapshot.revision(), snapshot.fingerprint())
                && evidenceSourceMatches(bundle.path("evidenceIndex").path("readinessSource"),
                "PACKAGE_READINESS_REPORT", readiness.reportId(), readiness.revision(),
                readiness.fingerprint())
                && evidenceSourceMatches(bundle.path("evidenceIndex")
                .path("businessAssetClosureSource"), "BUSINESS_ASSET_LINK_CLOSURE",
                closure.closureId(), closure.revision(), closure.fingerprint());
        require(closes, "REGISTRY_INGEST_BUNDLE_CLOSURE_INVALID");

        Instant exportedAt = instant(bundle.path("exportedAt").asText(),
                "REGISTRY_INGEST_BUNDLE_TIME_INVALID");
        Instant projectedAt = instant(bundle.path("evidenceIndex").path("projectedAt").asText(),
                "REGISTRY_INGEST_BUNDLE_TIME_INVALID");
        require(!exportedAt.isBefore(snapshot.createdAt())
                        && !exportedAt.isBefore(projectedAt),
                "REGISTRY_INGEST_BUNDLE_TIME_INVALID");
        return new VerifiedRegistryBundle(bundle.path("bundleId").asText(), revision,
                bundle.path("bundleFingerprint").asText(), packageId, snapshot.fingerprint(),
                evidence.fingerprint(), bundle.path("evidenceIndex")
                .path("projectionRevision").asLong(),
                bundle.path("dependencyManifest").size(), exportedAt);
    }

    /**
     * Verifies a signed ANEKE projection and its exact binding to a registry-ingest bundle.
     *
     * @param projection untrusted ANEKE projection
     * @param registryBundle exact Resource Gateway bundle supplied to ANEKE
     * @param trust caller-owned trust decision; never inferred by this library
     * @param observedAt time at which the projection is consumed
     * @return payload-free verified projection coordinates
     * @throws IllegalArgumentException when Schema, address, closure, time, or trust fails
     */
    public static VerifiedGovernanceProjection verifyGovernanceProjection(
            JsonNode projection,
            JsonNode registryBundle,
            ProjectionTrust trust,
            Instant observedAt) {
        VerifiedRegistryBundle bundle = verifyRegistryIngestBundle(registryBundle);
        BusinessMirrorSchemaValidator.require(projection, PROJECTION_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.GOVERNANCE_PROJECTION_INVALID");
        ObjectNode material = projectionMaterial(projection);
        ObjectNode signingEnvelope = material.objectNode();
        signingEnvelope.put("domain", PROJECTION_SIGNATURE_DOMAIN);
        signingEnvelope.put("schemaVersion", PACKAGE_GOVERNANCE_PROJECTION_V1);
        signingEnvelope.set("material", material.deepCopy());
        String materialFingerprint = fingerprint(signingEnvelope);
        JsonNode seal = projection.path("projectionSeal");
        require(materialFingerprint.equals(seal.path("materialFingerprint").asText()),
                "GOVERNANCE_PROJECTION_MATERIAL_FINGERPRINT_MISMATCH");

        ObjectNode address = material.objectNode();
        address.put("schemaVersion", PACKAGE_GOVERNANCE_PROJECTION_V1);
        address.put("projectionFingerprint", "");
        address.set("material", material.deepCopy());
        address.set("projectionSeal", seal.deepCopy());
        require(fingerprint(address).equals(projection.path("projectionFingerprint").asText()),
                "GOVERNANCE_PROJECTION_FINGERPRINT_MISMATCH");

        JsonNode snapshotRef = projection.path("packageSnapshotRef");
        JsonNode bundleRef = projection.path("registryIngestBundleRef");
        JsonNode evidenceRef = projection.path("evidenceIndexRef");
        boolean closes = projection.path("scope").equals(registryBundle.path("scope"))
                && artifactRefMatches(snapshotRef, "DOMAIN_CAPABILITY_PACKAGE",
                bundle.packageId(), bundle.revision(), bundle.packageSnapshotFingerprint())
                && artifactRefMatches(bundleRef, "PACKAGE_REGISTRY_INGEST_BUNDLE",
                bundle.bundleId(), bundle.revision(), bundle.bundleFingerprint())
                && artifactRefMatches(evidenceRef, "PACKAGE_EVIDENCE_INDEX",
                bundle.packageId(), bundle.evidenceProjectionRevision(),
                bundle.evidenceIndexFingerprint());
        require(closes, "GOVERNANCE_PROJECTION_BUNDLE_BINDING_MISMATCH");

        Instant producedAt = instant(projection.path("producedAt").asText(),
                "GOVERNANCE_PROJECTION_TIME_INVALID");
        Instant validFrom = instant(projection.path("validFrom").asText(),
                "GOVERNANCE_PROJECTION_TIME_INVALID");
        Instant expiresAt = instant(projection.path("expiresAt").asText(),
                "GOVERNANCE_PROJECTION_TIME_INVALID");
        Instant signedAt = instant(seal.path("signedAt").asText(),
                "GOVERNANCE_PROJECTION_TIME_INVALID");
        Instant now = Objects.requireNonNull(observedAt, "observedAt");
        require(!validFrom.isBefore(producedAt) && expiresAt.isAfter(validFrom)
                        && Duration.between(producedAt, expiresAt)
                        .compareTo(MAXIMUM_PROJECTION_LIFETIME) <= 0
                        && !signedAt.isBefore(producedAt) && signedAt.isBefore(expiresAt),
                "GOVERNANCE_PROJECTION_TIME_INVALID");
        require(!now.isBefore(validFrom) && now.isBefore(expiresAt),
                "GOVERNANCE_PROJECTION_EXPIRED");
        require(trust != null, "GOVERNANCE_PROJECTION_TRUST_UNAVAILABLE");
        boolean trusted;
        try {
            trusted = trust.verify(materialFingerprint, seal.path("algorithm").asText(),
                    seal.path("keyId").asText(), signedAt, seal.path("signature").asText());
        } catch (RuntimeException failure) {
            trusted = false;
        }
        require(trusted, "GOVERNANCE_PROJECTION_SIGNATURE_REJECTED");
        return new VerifiedGovernanceProjection(projection.path("projectionId").asText(),
                projection.path("revision").asLong(),
                projection.path("externalGeneration").asLong(),
                projection.path("projectionFingerprint").asText(),
                projection.path("status").asText(), bundle.packageId(),
                materialFingerprint, producedAt, validFrom, expiresAt,
                projection.path("issuer").asText());
    }

    /**
     * Validates the fail-closed joined governance view wire shape.
     *
     * @param view untrusted joined governance view
     * @throws IllegalArgumentException when the strict Schema rejects the view
     */
    public static void requireGovernanceView(JsonNode view) {
        BusinessMirrorSchemaValidator.require(view, VIEW_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.GOVERNANCE_VIEW_INVALID");
    }

    /**
     * Validates the idempotent projection-ingest receipt wire shape.
     *
     * @param receipt untrusted projection-ingest receipt
     * @throws IllegalArgumentException when the strict Schema rejects the receipt
     */
    public static void requireGovernanceReceipt(JsonNode receipt) {
        BusinessMirrorSchemaValidator.require(receipt, RECEIPT_SCHEMA_RESOURCE,
                "RG.BUSINESS_MIRROR.CLIENT.GOVERNANCE_RECEIPT_INVALID");
    }

    private static ObjectNode projectionMaterial(JsonNode projection) {
        ObjectNode material = projection.deepCopy();
        material.remove("schemaVersion");
        material.remove("projectionFingerprint");
        material.remove("projectionSeal");
        return material;
    }

    private static boolean artifactRefMatches(
            JsonNode ref, String kind, String id, long revision, String fingerprint) {
        return kind.equals(ref.path("kind").asText())
                && id.equals(ref.path("id").asText())
                && revision == ref.path("revision").asLong()
                && fingerprint.equals(ref.path("fingerprint").asText());
    }

    private static boolean evidenceSourceMatches(
            JsonNode ref, String kind, String id, long revision, String fingerprint) {
        return kind.equals(ref.path("kind").asText())
                && id.equals(ref.path("id").asText())
                && ("revision:" + revision).equals(ref.path("coordinate").asText())
                && fingerprint.equals(ref.path("fingerprint").asText());
    }

    private static ObjectNode object(JsonNode value, String code) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(code);
        }
        return object.deepCopy();
    }

    private static String fingerprint(JsonNode value) {
        return BusinessMirrorCanonical.fingerprint(value, TOO_LARGE, CANONICALIZATION_FAILED);
    }

    private static Instant instant(String value, String code) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw invalid(code);
        }
    }

    private static void require(boolean condition, String suffix) {
        if (!condition) {
            throw invalid(suffix);
        }
    }

    private static IllegalArgumentException invalid(String suffix) {
        return new IllegalArgumentException("RG.BUSINESS_MIRROR.CLIENT." + suffix);
    }

    /** Deployment-owned verification of the detached ANEKE signature. */
    @FunctionalInterface
    public interface ProjectionTrust {
        /**
         * Decides whether one exact detached signature is trusted.
         *
         * @param materialFingerprint domain-separated canonical signing material
         * @param algorithm declared signature algorithm
         * @param keyId deployment-resolved ANEKE key identity
         * @param signedAt declared signing time
         * @param signature detached Base64 signature
         * @return {@code true} only when key lifecycle, issuer policy, and signature are trusted
         */
        boolean verify(String materialFingerprint, String algorithm, String keyId,
                       Instant signedAt, String signature);
    }

    /**
     * Payload-free verified bundle coordinates safe for logs and build reports.
     *
     * @param bundleId immutable Bundle identity
     * @param revision exact Package compilation revision
     * @param bundleFingerprint canonical Bundle content address
     * @param packageId exact Package identity
     * @param packageSnapshotFingerprint canonical Snapshot content address
     * @param evidenceIndexFingerprint canonical Evidence Index content address
     * @param evidenceProjectionRevision exact Evidence projection revision
     * @param dependencyCount exact dependency-manifest size
     * @param exportedAt Bundle production time
     */
    public record VerifiedRegistryBundle(
            String bundleId,
            long revision,
            String bundleFingerprint,
            String packageId,
            String packageSnapshotFingerprint,
            String evidenceIndexFingerprint,
            long evidenceProjectionRevision,
            int dependencyCount,
            Instant exportedAt) {
    }

    /**
     * Payload-free verified external governance coordinates.
     *
     * @param projectionId immutable external stream identity
     * @param revision exact projection revision
     * @param externalGeneration monotonic ANEKE stream generation
     * @param projectionFingerprint canonical Projection content address
     * @param status ANEKE-owned governance state
     * @param packageId exact Package identity
     * @param materialFingerprint domain-separated signing-material content address
     * @param producedAt ANEKE production time
     * @param validFrom inclusive validity start
     * @param expiresAt exclusive validity end
     * @param issuer ANEKE projection issuer
     */
    public record VerifiedGovernanceProjection(
            String projectionId,
            long revision,
            long externalGeneration,
            String projectionFingerprint,
            String status,
            String packageId,
            String materialFingerprint,
            Instant producedAt,
            Instant validFrom,
            Instant expiresAt,
            String issuer) {
    }
}
