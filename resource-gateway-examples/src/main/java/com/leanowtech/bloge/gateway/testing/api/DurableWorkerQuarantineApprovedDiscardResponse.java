package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.Objects;

/**
 * Token-free receipt proving an independently approved quarantine discard.
 *
 * @param schemaVersion response protocol version
 * @param disposition newly discarded or idempotently replayed
 * @param key exact quarantine identity
 * @param ownerId verified maker identity
 * @param approvalId consumed checker approval identity
 * @param approverId distinct verified checker identity
 * @param approvalFingerprint immutable checker-decision fingerprint
 * @param authorizationMode external verification or legacy read-only replay
 * @param externalAuthorization verified reference, absent only for a legacy replay
 * @param reasonCode exact shared rationale
 * @param version resulting maintenance generation
 * @param actedAt database-clock discard time
 * @param receiptFingerprint canonical two-person receipt fingerprint
 * @param idempotentReplay whether the original immutable result was replayed
 */
public record DurableWorkerQuarantineApprovedDiscardResponse(
        String schemaVersion,
        String disposition,
        DurableWorkerQuarantineKey key,
        String ownerId,
        String approvalId,
        String approverId,
        String approvalFingerprint,
        String authorizationMode,
        DurableWorkerQuarantineChangeAuthorizationReference externalAuthorization,
        String reasonCode,
        long version,
        Instant actedAt,
        String receiptFingerprint,
        boolean idempotentReplay) {
    /** Current approved discard response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineApprovedDiscardResponse.v2";

    /** Validates complete token-free maker-checker evidence. */
    public DurableWorkerQuarantineApprovedDiscardResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        disposition = normalized(disposition);
        key = Objects.requireNonNull(key, "key");
        ownerId = normalized(ownerId);
        approvalId = normalized(approvalId);
        approverId = normalized(approverId);
        approvalFingerprint = normalized(approvalFingerprint);
        authorizationMode = normalized(authorizationMode);
        reasonCode = normalized(reasonCode);
        actedAt = Objects.requireNonNull(actedAt, "actedAt");
        receiptFingerprint = normalized(receiptFingerprint);
        if (disposition.isBlank() || ownerId.isBlank() || approvalId.isBlank()
                || approverId.isBlank() || ownerId.equals(approverId)
                || approvalFingerprint.isBlank() || reasonCode.isBlank() || version <= 0
                || receiptFingerprint.isBlank()
                || !(externalAuthorization == null
                ? "LEGACY_IN_PROCESS".equals(authorizationMode)
                : "EXTERNAL_VERIFIED".equals(authorizationMode))) {
            throw new IllegalArgumentException("A complete approved discard receipt is required");
        }
    }

    /** Creates a wire receipt from a successful approved discard. */
    public static DurableWorkerQuarantineApprovedDiscardResponse from(
            DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult result) {
        var receipt = result.receipt();
        return new DurableWorkerQuarantineApprovedDiscardResponse("",
                result.disposition().name(), new DurableWorkerQuarantineKey(
                receipt.key().runId(), receipt.key().checkpointFingerprint()),
                receipt.ownerId(), receipt.approvalId(), receipt.approverId(),
                receipt.approvalFingerprint(), receipt.externalAuthorization() == null
                        ? "LEGACY_IN_PROCESS" : "EXTERNAL_VERIFIED",
                receipt.externalAuthorization() == null ? null
                        : DurableWorkerQuarantineChangeAuthorizationReference.from(
                                receipt.externalAuthorization()),
                receipt.reasonCode(), receipt.version(),
                receipt.actedAt(), receipt.receiptFingerprint(),
                result.disposition() == DatabaseDurableWorkerQuarantineControlPlane
                        .ApprovedDiscardDisposition.IDEMPOTENT_REPLAY);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
