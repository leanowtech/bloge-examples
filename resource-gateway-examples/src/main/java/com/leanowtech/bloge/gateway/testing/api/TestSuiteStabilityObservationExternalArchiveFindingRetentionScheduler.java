package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodic database-leased lifecycle driver for external archive findings and derived evidence.
 *
 * <p>One tick independently bounds resolved-finding archival, archive purge, transition-event
 * retirement, and frozen-snapshot retirement. The control plane owns database-clock eligibility,
 * permanent evidence availability markers, exact row fingerprints, and cross-replica fencing.</p>
 */
public final class TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler.class);
    private static final Duration MAXIMUM_RETENTION = Duration.ofDays(3650);

    private final DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            controlPlane;
    private final Duration resolvedRetention;
    private final Duration archiveRetention;
    private final Duration evidenceRetention;
    private final int pageSize;

    /**
     * Creates a profile- and property-gated retention loop.
     *
     * @param controlPlane database-clock finding/evidence lifecycle authority
     * @param resolvedRetention active resolved-finding retention, one hour through ten years
     * @param archiveRetention resolved-finding archive retention, one day through ten years
     * @param evidenceRetention projection event/snapshot retention, one day through ten years
     * @param pageSize independent mutation bound from 1 through 500
     */
    public TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    controlPlane,
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration evidenceRetention,
            int pageSize) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.resolvedRetention = bounded(
                resolvedRetention, Duration.ofHours(1), "resolvedRetention");
        this.archiveRetention = bounded(
                archiveRetention, Duration.ofDays(1), "archiveRetention");
        this.evidenceRetention = bounded(
                evidenceRetention, Duration.ofDays(1), "evidenceRetention");
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException(
                    "External finding retention page must be 1 through 500");
        }
        this.pageSize = pageSize;
    }

    /**
     * Executes one leased retention page and contains transient storage failure for later retry.
     *
     * @return committed or lease-busy attempt, or {@code null} after a contained failure
     */
    @Scheduled(
            initialDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-initial-delay-ms:300000}",
            fixedDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.retention-interval-ms:3600000}")
    public DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            .RetentionAttempt retain() {
        try {
            var attempt = controlPlane.retain(
                    resolvedRetention, archiveRetention, evidenceRetention, pageSize);
            if (attempt.status()
                    == DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    .RetentionStatus.LEASE_BUSY) {
                return attempt;
            }
            var result = attempt.result();
            if (result.findingsArchived() > 0 || result.archivesPurged() > 0
                    || result.eventsDeleted() > 0 || result.snapshotsDeleted() > 0
                    || result.projectionRetired()) {
                log.info("External finding retention archived={}, purged={}, eventsDeleted={}, "
                                + "snapshotsDeleted={}, projectionRetired={}",
                        result.findingsArchived(), result.archivesPurged(),
                        result.eventsDeleted(), result.snapshotsDeleted(),
                        result.projectionRetired());
            }
            return attempt;
        } catch (RuntimeException unavailable) {
            log.warn("External finding retention failed; the last committed bounded page remains "
                    + "authoritative");
            return null;
        }
    }

    private static Duration bounded(Duration value, Duration minimum, String name) {
        Duration exact = Objects.requireNonNull(value, name);
        if (exact.compareTo(minimum) < 0 || exact.compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum
                    + " and " + MAXIMUM_RETENTION);
        }
        return exact;
    }
}
