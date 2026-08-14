package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceIndex;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Content-addressed, payload-free Package export consumed by ANEKE registry ingestion.
 *
 * <p>The bundle carries Resource Gateway immutable facts only. It does not contain, infer, or
 * grant an ANEKE registry state, publication decision, approval, or production certification.</p>
 */
public record PackageRegistryIngestBundle(
        String schemaVersion,
        String bundleFingerprint,
        String bundleId,
        long revision,
        CapabilitySnapshot.Scope scope,
        DomainCapabilityPackageSnapshot packageSnapshot,
        PackageReadinessReport readinessReport,
        BusinessAssetLinkClosure businessAssetLinkClosure,
        PackageEvidenceIndex evidenceIndex,
        List<MirrorArtifactRef> dependencyManifest,
        Instant exportedAt,
        String exporter
) {
    /** Current Package registry-ingest wire version introduced by integration protocol 1.1. */
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.packageRegistryIngestBundle.v1";
    /** Artifact kind referenced by ANEKE governance projections. */
    public static final String ARTIFACT_KIND = "PACKAGE_REGISTRY_INGEST_BUNDLE";

    /** Validates complete immutable fact closure without assigning governance authority. */
    public PackageRegistryIngestBundle {
        schemaVersion = version(schemaVersion);
        bundleFingerprint = optionalFingerprint(bundleFingerprint, "bundleFingerprint");
        bundleId = identifier(bundleId, "bundleId");
        if (revision < 1) {
            throw new IllegalArgumentException("registry ingest bundle revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        packageSnapshot = Objects.requireNonNull(packageSnapshot, "packageSnapshot");
        readinessReport = Objects.requireNonNull(readinessReport, "readinessReport");
        businessAssetLinkClosure = Objects.requireNonNull(
                businessAssetLinkClosure, "businessAssetLinkClosure");
        evidenceIndex = Objects.requireNonNull(evidenceIndex, "evidenceIndex");
        dependencyManifest = orderedRefs(dependencyManifest);
        exportedAt = Objects.requireNonNull(exportedAt, "exportedAt");
        exporter = identifier(exporter, "exporter");

        if (!scope.equals(packageSnapshot.scope())
                || !scope.equals(readinessReport.scope())
                || !scope.equals(businessAssetLinkClosure.scope())
                || !scope.equals(evidenceIndex.scope())
                || !packageSnapshot.packageId().equals(readinessReport.packageId())
                || !packageSnapshot.packageId().equals(businessAssetLinkClosure.packageId())
                || !packageSnapshot.packageId().equals(evidenceIndex.packageId())
                || revision != packageSnapshot.revision()
                || revision != readinessReport.revision()
                || revision != businessAssetLinkClosure.revision()
                || revision != evidenceIndex.compilationRevision()
                || !packageSnapshot.readinessReportRef().equals(readinessReport.artifactRef())
                || !packageSnapshot.businessAssetLinkClosureRef()
                .equals(businessAssetLinkClosure.artifactRef())
                || !dependencyManifest.equals(packageSnapshot.dependencyManifest())
                || !sourceMatches(evidenceIndex.packageSnapshotSource(),
                packageSnapshot.artifactRef())
                || !sourceMatches(evidenceIndex.readinessSource(), readinessReport.artifactRef())
                || !sourceMatches(evidenceIndex.businessAssetClosureSource(),
                businessAssetLinkClosure.artifactRef())
                || exportedAt.isBefore(packageSnapshot.createdAt())
                || exportedAt.isBefore(evidenceIndex.projectedAt())) {
            throw new IllegalArgumentException("Package registry ingest bundle closure is invalid");
        }
    }

    /** @return exact immutable bundle reference */
    public MirrorArtifactRef artifactRef() {
        if (bundleFingerprint.isBlank()) {
            throw new IllegalStateException("Package registry ingest bundle is not content-addressed");
        }
        return new MirrorArtifactRef(ARTIFACT_KIND, bundleId, revision, bundleFingerprint);
    }

    /** @return identical bundle with a replacement canonical fingerprint */
    public PackageRegistryIngestBundle withFingerprint(String fingerprint) {
        return new PackageRegistryIngestBundle(schemaVersion, fingerprint, bundleId, revision,
                scope, packageSnapshot, readinessReport, businessAssetLinkClosure, evidenceIndex,
                dependencyManifest, exportedAt, exporter);
    }

    private static boolean sourceMatches(
            PackageEvidenceIndex.EvidenceSource source, MirrorArtifactRef ref) {
        return source.kind().equals(ref.kind())
                && source.id().equals(ref.id())
                && source.coordinate().equals("revision:" + ref.revision())
                && source.fingerprint().equals(ref.fingerprint());
    }

    private static List<MirrorArtifactRef> orderedRefs(List<MirrorArtifactRef> values) {
        if (values == null || values.isEmpty() || values.size() > 4_096) {
            throw new IllegalArgumentException("dependencyManifest must be non-empty and bounded");
        }
        List<MirrorArtifactRef> exact = values.stream()
                .map(value -> Objects.requireNonNull(value, "dependencyManifest item"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (exact.stream().distinct().count() != exact.size()) {
            throw new IllegalArgumentException("dependencyManifest must be unique");
        }
        return List.copyOf(exact);
    }

    static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isEmpty()) {
            fingerprint(exact, field);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException("unsupported Package registry ingest bundle version");
        }
        return exact;
    }
}
