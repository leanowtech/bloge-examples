package com.leanowtech.bloge.gateway.businessmirror.impact;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Cross-replica leased worker for the rebuildable Business Asset impact projection. */
public final class BusinessAssetImpactProjectionWorker {
    private static final String FAILURE_CODE = "RG.BUSINESS_MIRROR.IMPACT_PROJECTION_FAILED";

    private final BusinessAssetImpactService service;
    private final String ownerId;
    private final Duration leaseDuration;
    private final int maximumAttempts;
    private final int maximumBatchSize;

    public BusinessAssetImpactProjectionWorker(BusinessAssetImpactService service) {
        this(service, "business-asset-impact-" + UUID.randomUUID(), Duration.ofMinutes(2), 8, 50);
    }

    BusinessAssetImpactProjectionWorker(
            BusinessAssetImpactService service,
            String ownerId,
            Duration leaseDuration,
            int maximumAttempts,
            int maximumBatchSize) {
        this.service = Objects.requireNonNull(service, "service");
        this.ownerId = required(ownerId);
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()
                || maximumAttempts < 1 || maximumAttempts > 100
                || maximumBatchSize < 1 || maximumBatchSize > 500) {
            throw new IllegalArgumentException("Business asset impact worker policy is invalid");
        }
        this.maximumAttempts = maximumAttempts;
        this.maximumBatchSize = maximumBatchSize;
    }

    /** Drains a bounded batch so one hot queue cannot monopolize the scheduler. */
    @Scheduled(fixedDelayString =
            "${gateway.business-mirror.impact.worker-fixed-delay-ms:2000}")
    public void drain() {
        for (int index = 0; index < maximumBatchSize; index++) {
            Disposition disposition = runOnce().disposition();
            if (disposition == Disposition.NO_WORK
                    || disposition == Disposition.CONTROL_UNAVAILABLE) {
                return;
            }
        }
    }

    /** Executes one fail-closed claim/project/complete turn without exposing business payload. */
    public Turn runOnce() {
        BusinessAssetImpactRepository.ProjectionLease lease;
        try {
            lease = service.claim(ownerId, leaseDuration).orElse(null);
        } catch (RuntimeException unavailable) {
            return new Turn(Disposition.CONTROL_UNAVAILABLE, "", 0, "");
        }
        if (lease == null) {
            return new Turn(Disposition.NO_WORK, "", 0, "");
        }
        try {
            BusinessAssetImpactRepository.ProjectionResult result = service.consume(lease);
            return new Turn(result.replayed() ? Disposition.REPLAYED : Disposition.PROJECTED,
                    lease.packageId(), lease.attemptCount(), "");
        } catch (RuntimeException failure) {
            try {
                BusinessAssetImpactRepository.ProjectionRelease released = service.release(
                        lease, FAILURE_CODE, maximumAttempts);
                Disposition disposition = released.status()
                        == BusinessAssetImpactRepository.ProjectionJobStatus.QUARANTINED
                        ? Disposition.QUARANTINED : Disposition.RETRY_SCHEDULED;
                return new Turn(disposition, lease.packageId(), released.attemptCount(),
                        FAILURE_CODE);
            } catch (RuntimeException unavailable) {
                return new Turn(Disposition.CONTROL_UNAVAILABLE, lease.packageId(),
                        lease.attemptCount(), FAILURE_CODE);
            }
        }
    }

    public enum Disposition {
        NO_WORK,
        PROJECTED,
        REPLAYED,
        RETRY_SCHEDULED,
        QUARANTINED,
        CONTROL_UNAVAILABLE
    }

    /** Payload-free operational result for one worker turn. */
    public record Turn(
            Disposition disposition, String packageId, int attemptCount, String failureCode) {
        public Turn {
            disposition = Objects.requireNonNull(disposition, "disposition");
            packageId = packageId == null ? "" : packageId.trim();
            failureCode = failureCode == null ? "" : failureCode.trim();
            if (attemptCount < 0 || disposition == Disposition.NO_WORK && !packageId.isBlank()) {
                throw new IllegalArgumentException("Business asset impact worker turn is invalid");
            }
        }
    }

    private static String required(String value) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException("Business asset impact worker owner is invalid");
        }
        return exact;
    }
}
