package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable, rebuildable reverse index derived from immutable Package compilation facts. */
public interface BusinessAssetImpactRepository {
    ProjectionResult project(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt);

    ImpactQuery query(
            CapabilitySnapshot.Scope scope,
            BusinessAssetSelector selector,
            String afterPackageId,
            int limit);

    List<SnapshotCoordinate> staleSnapshots(
            CapabilitySnapshot.Scope scope, String afterPackageId, int limit);

    /** Admits one exact immutable Snapshot coordinate to the transactional projection outbox. */
    default boolean enqueue(
            CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        throw new UnsupportedOperationException("Business asset impact outbox is unavailable");
    }

    /** Claims the oldest available outbox item under a database-clock lease. */
    default Optional<ProjectionLease> claim(String leaseOwner, Duration leaseDuration) {
        return Optional.empty();
    }

    /** Marks a lease completed in the same transaction as its projection and change event. */
    default boolean complete(ProjectionLease lease) {
        return false;
    }

    /** Releases a failed lease with bounded retry or quarantine. */
    default ProjectionRelease release(
            ProjectionLease lease, String failureCode, int maximumAttempts) {
        return new ProjectionRelease(ProjectionJobStatus.QUARANTINED, 0, null);
    }

    record ProjectionResult(
            String packageId,
            long compilationRevision,
            String snapshotFingerprint,
            String closureFingerprint,
            int sourceCount,
            int pathCount,
            Instant projectedAt,
            boolean replayed
    ) {
    }

    record ImpactQuery(
            List<StoredPackageImpact> items,
            String nextCursor,
            List<String> stalePackageIds,
            boolean stalePackageIdsTruncated,
            Instant projectedThrough
    ) {
        public ImpactQuery {
            items = items == null ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null ? "" : nextCursor.trim();
            stalePackageIds = stalePackageIds == null ? List.of() : List.copyOf(stalePackageIds);
        }
    }

    record StoredPackageImpact(
            String packageId,
            long compilationRevision,
            MirrorArtifactRef packageSnapshotRef,
            MirrorArtifactRef businessAssetLinkClosureRef,
            List<BusinessAssetImpactProjection.SourceImpact> matches
    ) {
        public StoredPackageImpact {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    record SnapshotCoordinate(String packageId, long compilationRevision) {
    }

    enum ProjectionJobStatus {
        PENDING,
        PROJECTING,
        COMPLETED,
        QUARANTINED
    }

    record ProjectionLease(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            String snapshotFingerprint,
            String leaseOwner,
            long leaseEpoch,
            int attemptCount,
            Instant leaseExpiresAt
    ) {
    }

    record ProjectionRelease(
            ProjectionJobStatus status, int attemptCount, Instant availableAt) {
    }
}
