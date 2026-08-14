package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable append-only Package evidence indexes, current heads, outbox, and owner-task journal. */
public interface PackageEvidenceRepository {
    boolean enqueue(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt);

    Optional<ProjectionLease> claim(String leaseOwner, Duration leaseDuration);

    ProjectionRelease release(ProjectionLease lease, String failureCode, int maximumAttempts);

    boolean complete(ProjectionLease lease);

    ProjectionReservation reserveProjectionRevision(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision);

    ProjectionResult append(PackageEvidenceIndex index, String deepLink);

    Optional<PackageEvidenceIndex> findCurrent(
            CapabilitySnapshot.Scope scope, String packageId);

    Optional<PackageEvidenceIndex> find(
            CapabilitySnapshot.Scope scope, String packageId, long projectionRevision);

    /** Returns the newest evidence projection for one exact Package compilation. */
    Optional<PackageEvidenceIndex> findByCompilation(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision);

    CurrentPage findCurrentByDomain(
            CapabilitySnapshot.Scope scope, String domainId, String afterPackageId, int limit);

    List<EvidenceOwnerTask> findTasks(
            CapabilitySnapshot.Scope scope,
            String domainId,
            String packageId,
            EvidenceOwnerTask.Status status,
            int limit);

    Optional<EvidenceOwnerTask> findTask(CapabilitySnapshot.Scope scope, String taskId);

    EvidenceOwnerTask transitionTask(
            CapabilitySnapshot.Scope scope,
            String taskId,
            long expectedVersion,
            EvidenceOwnerTask.Status target,
            String actor,
            MirrorArtifactRef resolutionEvidenceRef,
            Instant at);

    record ProjectionLease(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            String snapshotFingerprint,
            String leaseOwner,
            long leaseEpoch,
            int attemptCount,
            Instant leaseExpiresAt) {
    }

    enum ProjectionJobStatus {
        PENDING,
        PROJECTING,
        COMPLETED,
        QUARANTINED
    }

    record ProjectionRelease(
            ProjectionJobStatus status, int attemptCount, Instant availableAt) {
    }

    record ProjectionResult(
            String packageId,
            long compilationRevision,
            long projectionRevision,
            String indexFingerprint,
            int ownerTaskCount,
            Instant projectedAt,
            boolean replayed) {
    }

    /** Package-locked projection sequence and database-time evidence cut. */
    record ProjectionReservation(long projectionRevision, Instant reservedAt) {
        public ProjectionReservation {
            if (projectionRevision < 1 || reservedAt == null) {
                throw new IllegalArgumentException("projection reservation is invalid");
            }
        }
    }

    record CurrentPage(List<PackageEvidenceIndex> items, String nextCursor) {
        public CurrentPage {
            items = items == null ? List.of() : List.copyOf(items);
            nextCursor = nextCursor == null ? "" : nextCursor.trim();
        }
    }

    /** Stable stale-writer signal for an older Package compilation. */
    final class StaleProjectionException extends RuntimeException {
        public StaleProjectionException() {
            super("Package evidence projection is older than the current Package compilation");
        }
    }

    /** Stable exact-coordinate content conflict. */
    final class ProjectionDriftException extends RuntimeException {
        public ProjectionDriftException() {
            super("Package evidence projection coordinate resolved to different content");
        }
    }

    /** Optimistic task lifecycle conflict. */
    final class TaskVersionConflictException extends RuntimeException {
        public TaskVersionConflictException() {
            super("Evidence owner task changed after the requested version");
        }
    }

    /** Stable scope-safe signal for a missing owner task. */
    final class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException() {
            super("Evidence owner task was not found");
        }
    }
}
