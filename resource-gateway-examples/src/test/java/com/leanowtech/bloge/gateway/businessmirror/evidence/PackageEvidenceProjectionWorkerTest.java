package com.leanowtech.bloge.gateway.businessmirror.evidence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackageEvidenceProjectionWorkerTest {
    @Test
    void reportsProjectedAndQuarantinedTurnsWithoutLeakingFailureMaterial() {
        PackageEvidenceService service = mock(PackageEvidenceService.class);
        PackageEvidenceRepository.ProjectionLease lease = lease();
        when(service.claim("worker-a", Duration.ofMinutes(2)))
                .thenReturn(Optional.of(lease), Optional.of(lease));
        when(service.consume(lease))
                .thenReturn(new PackageEvidenceRepository.ProjectionResult(
                        "package-a", 7, 1, "sha256:" + "a".repeat(64),
                        2, PackageEvidenceFixtures.NOW, false))
                .thenThrow(new IllegalStateException("payload-must-not-escape"));
        when(service.release(lease,
                "RG.BUSINESS_MIRROR.EVIDENCE_PROJECTION_FAILED", 2))
                .thenReturn(new PackageEvidenceRepository.ProjectionRelease(
                        PackageEvidenceRepository.ProjectionJobStatus.QUARANTINED,
                        2, null));
        PackageEvidenceProjectionWorker worker = new PackageEvidenceProjectionWorker(
                service, "worker-a", Duration.ofMinutes(2), 2, 1);

        assertThat(worker.runOnce().disposition())
                .isEqualTo(PackageEvidenceProjectionWorker.Disposition.PROJECTED);
        PackageEvidenceProjectionWorker.Turn failed = worker.runOnce();
        assertThat(failed.disposition())
                .isEqualTo(PackageEvidenceProjectionWorker.Disposition.QUARANTINED);
        assertThat(failed.failureCode())
                .isEqualTo("RG.BUSINESS_MIRROR.EVIDENCE_PROJECTION_FAILED");
        assertThat(failed.toString()).doesNotContain("payload-must-not-escape");
    }

    @Test
    void treatsUnavailableControlPlaneAsAStableOperationalDisposition() {
        PackageEvidenceService service = mock(PackageEvidenceService.class);
        when(service.claim("worker-a", Duration.ofMinutes(2)))
                .thenThrow(new IllegalStateException("database unavailable"));
        PackageEvidenceProjectionWorker worker = new PackageEvidenceProjectionWorker(
                service, "worker-a", Duration.ofMinutes(2), 8, 1);

        assertThat(worker.runOnce().disposition())
                .isEqualTo(PackageEvidenceProjectionWorker.Disposition.CONTROL_UNAVAILABLE);
    }

    private static PackageEvidenceRepository.ProjectionLease lease() {
        return new PackageEvidenceRepository.ProjectionLease(
                PackageEvidenceFixtures.SCOPE, "package-a", 7,
                "sha256:" + "b".repeat(64), "worker-a", 1, 2,
                PackageEvidenceFixtures.NOW.plusSeconds(120));
    }
}
