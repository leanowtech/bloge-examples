package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable claim and append-only result store for side-effect reconciliation. */
public interface SideEffectReconciliationRepository {

    Claim claim(ClaimRequest request);

    SideEffectReconciliationRecord complete(String runId,
                                            String attemptId,
                                            String ownerToken,
                                            SideEffectReconciliationRecord record);

    Optional<SideEffectReconciliationRecord> find(String runId, String attemptId);

    Optional<SideEffectReconciliationRecord> findByRequestId(String requestId);

    List<SideEffectReconciliationRecord> forRun(String runId);

    boolean available();

    enum ClaimStatus {
        ACQUIRED,
        RESOLVED,
        IN_PROGRESS,
        REQUEST_CONFLICT,
        TARGET_CONFLICT
    }

    record ClaimRequest(String runId, String attemptId, String requestId, String requestFingerprint,
                        String tenantId, String environmentId, String ownerToken,
                        Instant claimedAt, Instant leaseUntil) {
    }

    record Claim(ClaimStatus status, SideEffectReconciliationRecord existing,
                 String ownerToken, Instant leaseUntil) {
        static Claim acquired(String ownerToken, Instant leaseUntil) {
            return new Claim(ClaimStatus.ACQUIRED, null, ownerToken, leaseUntil);
        }

        static Claim existing(ClaimStatus status, SideEffectReconciliationRecord record) {
            return new Claim(status, record, "", Instant.EPOCH);
        }

        static Claim pending(Instant leaseUntil) {
            return new Claim(ClaimStatus.IN_PROGRESS, null, "", leaseUntil);
        }
    }
}
