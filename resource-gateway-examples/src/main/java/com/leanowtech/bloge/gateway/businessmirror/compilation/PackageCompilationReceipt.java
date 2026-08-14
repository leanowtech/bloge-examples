package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;

import java.time.Instant;
import java.util.regex.Pattern;

/** Exact durable response and immutable fact index for one Package compilation command. */
public record PackageCompilationReceipt(
        String schemaVersion,
        String requestFingerprint,
        String packageId,
        long sourceDraftRevision,
        String sourceDraftFingerprint,
        long compilationRevision,
        PackageReadinessReport readiness,
        BusinessAssetLinkClosure businessAssetLinkClosure,
        DomainCapabilityPackageSnapshot snapshot,
        String authorityGeneration,
        Instant completedAt
) {
    /** Current compile receipt wire protocol. */
    public static final String SCHEMA_VERSION = "resourceGateway.packageCompilationReceipt.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Verifies all embedded facts describe one source revision and one atomic compilation. */
    public PackageCompilationReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = normalized(requestFingerprint);
        packageId = normalized(packageId);
        sourceDraftFingerprint = normalized(sourceDraftFingerprint);
        readiness = java.util.Objects.requireNonNull(readiness, "readiness");
        businessAssetLinkClosure = java.util.Objects.requireNonNull(
                businessAssetLinkClosure, "businessAssetLinkClosure");
        authorityGeneration = normalized(authorityGeneration);
        completedAt = java.util.Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || packageId.isBlank() || sourceDraftRevision < 1
                || !FINGERPRINT.matcher(sourceDraftFingerprint).matches()
                || compilationRevision < 1 || authorityGeneration.isBlank()
                || !packageId.equals(readiness.packageId())
                || sourceDraftRevision != readiness.sourceDraftRevision()
                || !sourceDraftFingerprint.equals(readiness.sourceDraftFingerprint())
                || compilationRevision != readiness.revision()
                || !packageId.equals(businessAssetLinkClosure.packageId())
                || compilationRevision != businessAssetLinkClosure.revision()
                || !readiness.scope().equals(businessAssetLinkClosure.scope())
                || !completedAt.equals(readiness.createdAt())
                || !completedAt.equals(businessAssetLinkClosure.createdAt())
                || (readiness.status() == PackageReadinessReport.Status.BLOCKED) != (snapshot == null)) {
            throw new IllegalArgumentException("Package compilation receipt facts are inconsistent");
        }
        if (snapshot != null && (!packageId.equals(snapshot.packageId())
                || sourceDraftRevision != snapshot.sourceDraftRevision()
                || !sourceDraftFingerprint.equals(snapshot.sourceDraftFingerprint())
                || compilationRevision != snapshot.revision()
                || !readiness.scope().equals(snapshot.scope())
                || !readiness.artifactRef().equals(snapshot.readinessReportRef())
                || !businessAssetLinkClosure.artifactRef().equals(snapshot.businessAssetLinkClosureRef())
                || !completedAt.equals(snapshot.createdAt()))) {
            throw new IllegalArgumentException("Package compilation snapshot is inconsistent");
        }
    }

    /** Builds a stable receipt from a fenced compiler result. */
    public static PackageCompilationReceipt completed(
            String requestFingerprint, PackageCompilationResult result) {
        PackageCompilationResult exact = java.util.Objects.requireNonNull(result, "result");
        PackageReadinessReport readiness = exact.readiness();
        return new PackageCompilationReceipt(SCHEMA_VERSION, requestFingerprint,
                readiness.packageId(), readiness.sourceDraftRevision(),
                readiness.sourceDraftFingerprint(), readiness.revision(), readiness,
                exact.businessAssetLinkClosure(), exact.snapshot(),
                exact.frozenDependencies().authorityGeneration(), readiness.createdAt());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
