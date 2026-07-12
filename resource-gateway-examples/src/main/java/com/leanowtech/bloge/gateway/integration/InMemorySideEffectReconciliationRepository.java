package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-memory implementation used by focused integration tests. */
public final class InMemorySideEffectReconciliationRepository
        implements SideEffectReconciliationRepository {
    private final Map<String, Head> heads = new LinkedHashMap<>();
    private final Map<String, String> requestTargets = new LinkedHashMap<>();

    @Override
    public synchronized Claim claim(ClaimRequest request) {
        String targetKey = key(request.runId(), request.attemptId());
        String requestTarget = requestTargets.get(request.requestId());
        if (requestTarget != null && !requestTarget.equals(targetKey)) {
            return Claim.existing(ClaimStatus.REQUEST_CONFLICT, null);
        }
        Head current = heads.get(targetKey);
        if (current == null) {
            heads.put(targetKey, Head.claimed(request));
            requestTargets.put(request.requestId(), targetKey);
            return Claim.acquired(request.ownerToken(), request.leaseUntil());
        }
        if (current.record != null) {
            ClaimStatus status = current.requestFingerprint.equals(request.requestFingerprint())
                    ? ClaimStatus.RESOLVED : ClaimStatus.TARGET_CONFLICT;
            return Claim.existing(status, current.record);
        }
        if (current.requestId.equals(request.requestId())
                && !current.requestFingerprint.equals(request.requestFingerprint())) {
            return Claim.existing(ClaimStatus.REQUEST_CONFLICT, null);
        }
        if (current.leaseUntil.isAfter(request.claimedAt())) {
            return Claim.pending(current.leaseUntil);
        }
        requestTargets.remove(current.requestId);
        heads.put(targetKey, Head.claimed(request));
        requestTargets.put(request.requestId(), targetKey);
        return Claim.acquired(request.ownerToken(), request.leaseUntil());
    }

    @Override
    public synchronized SideEffectReconciliationRecord complete(String runId,
                                                                String attemptId,
                                                                String ownerToken,
                                                                SideEffectReconciliationRecord record) {
        String targetKey = key(runId, attemptId);
        Head current = heads.get(targetKey);
        if (current == null || current.record != null || !current.ownerToken.equals(ownerToken)) {
            throw new IllegalStateException("Reconciliation claim is no longer owned");
        }
        if (!record.fingerprintVerified()) {
            throw new IllegalArgumentException("Reconciliation record fingerprint is invalid");
        }
        heads.put(targetKey, current.resolved(record));
        return record;
    }

    @Override
    public synchronized Optional<SideEffectReconciliationRecord> find(String runId, String attemptId) {
        Head head = heads.get(key(runId, attemptId));
        return head == null ? Optional.empty() : Optional.ofNullable(head.record);
    }

    @Override
    public synchronized Optional<SideEffectReconciliationRecord> findByRequestId(String requestId) {
        String target = requestTargets.get(requestId);
        Head head = target == null ? null : heads.get(target);
        return head == null ? Optional.empty() : Optional.ofNullable(head.record);
    }

    @Override
    public synchronized List<SideEffectReconciliationRecord> forRun(String runId) {
        return heads.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(runId + "\u0000"))
                .map(Map.Entry::getValue)
                .map(head -> head.record)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(record -> record.chain().sequence()))
                .toList();
    }

    @Override
    public boolean available() {
        return true;
    }

    private static String key(String runId, String attemptId) {
        return runId + "\u0000" + attemptId;
    }

    private record Head(String requestId, String requestFingerprint, String ownerToken,
                        Instant leaseUntil, SideEffectReconciliationRecord record) {
        static Head claimed(ClaimRequest request) {
            return new Head(request.requestId(), request.requestFingerprint(), request.ownerToken(),
                    request.leaseUntil(), null);
        }

        Head resolved(SideEffectReconciliationRecord value) {
            return new Head(requestId, requestFingerprint, "", Instant.EPOCH, value);
        }
    }
}
