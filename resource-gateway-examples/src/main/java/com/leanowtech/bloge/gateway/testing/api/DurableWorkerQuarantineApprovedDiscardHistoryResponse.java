package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.List;

/**
 * Bounded token-free history proving maker-checker separation for every approved discard.
 *
 * @param schemaVersion response protocol version
 * @param history newest approved discard evidence first
 */
public record DurableWorkerQuarantineApprovedDiscardHistoryResponse(
        String schemaVersion,
        List<DiscardReceipt> history) {
    /** Current approved discard history protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineApprovedDiscardHistoryResponse.v2";

    /** Copies immutable history. */
    public DurableWorkerQuarantineApprovedDiscardHistoryResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Creates a wire page from integrity-verified retained evidence. */
    public static DurableWorkerQuarantineApprovedDiscardHistoryResponse from(
            List<DatabaseDurableWorkerQuarantineControlPlane
                    .ApprovedDiscardHistoryRecord> records) {
        return new DurableWorkerQuarantineApprovedDiscardHistoryResponse("",
                records == null ? List.of() : records.stream().map(DiscardReceipt::from).toList());
    }

    /** Token-free retained maker-checker discard evidence. */
    public record DiscardReceipt(
            String historyId,
            DurableWorkerQuarantineKey key,
            String quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String reasonCode,
            String ownerId,
            String approvalId,
            String approverId,
            String approvalFingerprint,
            String authorizationMode,
            DurableWorkerQuarantineChangeAuthorizationReference externalAuthorization,
            long version,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        /** Enforces an explicit external or legacy authorization mode. */
        public DiscardReceipt {
            authorizationMode = normalized(authorizationMode);
            if (!(externalAuthorization == null
                    ? "LEGACY_IN_PROCESS".equals(authorizationMode)
                    : "EXTERNAL_VERIFIED".equals(authorizationMode))) {
                throw new IllegalArgumentException(
                        "Approved discard history authorization mode is invalid");
            }
        }

        private static DiscardReceipt from(
                DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardHistoryRecord record) {
            return new DiscardReceipt(record.historyId(), new DurableWorkerQuarantineKey(
                    record.key().runId(), record.key().checkpointFingerprint()),
                    record.quarantineReason().name(), record.consecutiveFailures(),
                    record.quarantineThreshold(), record.firstObservedAt(),
                    record.quarantinedAt(), record.reasonCode(), record.ownerId(),
                    record.approvalId(), record.approverId(), record.approvalFingerprint(),
                    record.externalAuthorization() == null
                            ? "LEGACY_IN_PROCESS" : "EXTERNAL_VERIFIED",
                    record.externalAuthorization() == null ? null
                            : DurableWorkerQuarantineChangeAuthorizationReference.from(
                                    record.externalAuthorization()),
                    record.version(), record.actedAt(), record.receiptFingerprint(),
                    record.recordFingerprint());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
