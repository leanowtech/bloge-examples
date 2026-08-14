package com.leanowtech.bloge.gateway.businessmirror.impact;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAssetImpactProjectionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void projectsOneClaimAndReportsAnEmptyQueueWithoutLeakingPayload() {
        BusinessAssetImpactService service = mock(BusinessAssetImpactService.class);
        BusinessAssetImpactRepository.ProjectionLease lease = lease(1);
        when(service.claim("worker-a", Duration.ofMinutes(2)))
                .thenReturn(Optional.of(lease), Optional.empty());
        when(service.consume(lease)).thenReturn(new BusinessAssetImpactRepository.ProjectionResult(
                "refund-package", 7, fingerprint('a'), fingerprint('b'),
                5, 10, NOW, false));
        BusinessAssetImpactProjectionWorker worker = new BusinessAssetImpactProjectionWorker(
                service, "worker-a", Duration.ofMinutes(2), 3, 10);

        assertThat(worker.runOnce().disposition())
                .isEqualTo(BusinessAssetImpactProjectionWorker.Disposition.PROJECTED);
        assertThat(worker.runOnce().disposition())
                .isEqualTo(BusinessAssetImpactProjectionWorker.Disposition.NO_WORK);
        verify(service).consume(lease);
    }

    @Test
    void releasesFailuresIntoRetryThenSurfacesQuarantine() {
        BusinessAssetImpactService service = mock(BusinessAssetImpactService.class);
        BusinessAssetImpactRepository.ProjectionLease lease = lease(3);
        when(service.claim("worker-a", Duration.ofMinutes(2))).thenReturn(Optional.of(lease));
        when(service.consume(lease)).thenThrow(new IllegalStateException("sensitive provider detail"));
        when(service.release(lease, "RG.BUSINESS_MIRROR.IMPACT_PROJECTION_FAILED", 3))
                .thenReturn(new BusinessAssetImpactRepository.ProjectionRelease(
                        BusinessAssetImpactRepository.ProjectionJobStatus.QUARANTINED, 3, null));
        BusinessAssetImpactProjectionWorker worker = new BusinessAssetImpactProjectionWorker(
                service, "worker-a", Duration.ofMinutes(2), 3, 10);

        BusinessAssetImpactProjectionWorker.Turn turn = worker.runOnce();

        assertThat(turn.disposition())
                .isEqualTo(BusinessAssetImpactProjectionWorker.Disposition.QUARANTINED);
        assertThat(turn.failureCode())
                .isEqualTo("RG.BUSINESS_MIRROR.IMPACT_PROJECTION_FAILED");
        assertThat(turn.toString()).doesNotContain("sensitive provider detail");
    }

    @Test
    void stopsTheCurrentDrainBatchWhenTheControlDatabaseIsUnavailable() {
        BusinessAssetImpactService service = mock(BusinessAssetImpactService.class);
        when(service.claim("worker-a", Duration.ofMinutes(2)))
                .thenThrow(new IllegalStateException("database unavailable"));
        BusinessAssetImpactProjectionWorker worker = new BusinessAssetImpactProjectionWorker(
                service, "worker-a", Duration.ofMinutes(2), 3, 10);

        worker.drain();

        verify(service).claim("worker-a", Duration.ofMinutes(2));
    }

    private static BusinessAssetImpactRepository.ProjectionLease lease(int attempt) {
        return new BusinessAssetImpactRepository.ProjectionLease(
                BusinessAssetImpactFixtures.SCOPE, "refund-package", 7, fingerprint('a'),
                "worker-a", attempt, attempt, NOW.plusSeconds(60));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
