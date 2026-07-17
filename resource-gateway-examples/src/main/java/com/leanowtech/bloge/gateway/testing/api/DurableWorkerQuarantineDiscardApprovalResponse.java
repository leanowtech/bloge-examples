package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Token-free immutable checker approval for one exact discard claim.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly approved or idempotently replayed
 * @param approvalId opaque approval identity consumed by the maker
 * @param key exact quarantine identity
 * @param claimOwner verified maker identity
 * @param claimVersion exact maintenance generation
 * @param claimUntil exact claim deadline
 * @param approverId distinct verified checker identity
 * @param reasonCode exact shared rationale
 * @param approvedAt database-clock checker decision time
 * @param approvalUntil database-clock deadline no later than the claim deadline
 * @param externalAuthorization verified key-free external governance reference
 * @param approvalFingerprint immutable checker-decision fingerprint
 * @param idempotentReplay whether the original immutable decision was replayed
 */
public record DurableWorkerQuarantineDiscardApprovalResponse(
        String schemaVersion,
        String disposition,
        String approvalId,
        DurableWorkerQuarantineKey key,
        String claimOwner,
        long claimVersion,
        Instant claimUntil,
        String approverId,
        String reasonCode,
        Instant approvedAt,
        Instant approvalUntil,
        DurableWorkerQuarantineChangeAuthorizationReference externalAuthorization,
        String approvalFingerprint,
        boolean idempotentReplay) {
    /** Current checker approval response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineDiscardApprovalResponse.v2";

    /** Validates complete token-free checker evidence. */
    public DurableWorkerQuarantineDiscardApprovalResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        approvalId = normalized(approvalId);
        key = Objects.requireNonNull(key, "key");
        claimOwner = normalized(claimOwner);
        claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
        approverId = normalized(approverId);
        reasonCode = normalized(reasonCode);
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
        approvalUntil = Objects.requireNonNull(approvalUntil, "approvalUntil");
        externalAuthorization = Objects.requireNonNull(
                externalAuthorization, "externalAuthorization");
        approvalFingerprint = normalized(approvalFingerprint);
        if (disposition.isBlank() || approvalId.isBlank() || claimOwner.isBlank()
                || claimVersion <= 0 || approverId.isBlank() || reasonCode.isBlank()
                || approvalFingerprint.isBlank() || claimOwner.equals(approverId)) {
            throw new IllegalArgumentException("A complete discard approval is required");
        }
    }

    /** Creates a wire response from a successful checker command. */
    public static DurableWorkerQuarantineDiscardApprovalResponse from(
            DatabaseDurableWorkerQuarantineControlPlane.DiscardApprovalResult result) {
        var approval = result.approval();
        return new DurableWorkerQuarantineDiscardApprovalResponse("",
                result.disposition().name(), approval.approvalId(),
                new DurableWorkerQuarantineKey(
                        approval.key().runId(), approval.key().checkpointFingerprint()),
                approval.claimOwner(), approval.claimVersion(), approval.claimUntil(),
                approval.approverId(), approval.reasonCode(), approval.approvedAt(),
                approval.approvalUntil(),
                DurableWorkerQuarantineChangeAuthorizationReference.from(
                        approval.externalAuthorization()),
                approval.approvalFingerprint(),
                result.disposition() == DatabaseDurableWorkerQuarantineControlPlane
                        .DiscardApprovalDisposition.IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
