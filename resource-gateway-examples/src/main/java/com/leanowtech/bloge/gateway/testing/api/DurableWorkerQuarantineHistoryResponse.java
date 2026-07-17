package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.List;

/**
 * Bounded immutable token-free quarantine action history.
 *
 * @param schemaVersion response protocol version
 * @param history newest action evidence first
 */
public record DurableWorkerQuarantineHistoryResponse(
        String schemaVersion,
        List<ActionReceipt> history) {
    /** Current history response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineHistoryResponse.v1";

    /** Copies immutable historical evidence. */
    public DurableWorkerQuarantineHistoryResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Creates a wire history page from integrity-verified retained records. */
    public static DurableWorkerQuarantineHistoryResponse from(
            List<DatabaseDurableWorkerQuarantineControlPlane.QuarantineHistoryRecord> records) {
        return new DurableWorkerQuarantineHistoryResponse("", records == null ? List.of()
                : records.stream().map(ActionReceipt::from).toList());
    }

    /** Token-free retained action evidence. */
    public record ActionReceipt(
            String historyId,
            DurableWorkerQuarantineKey key,
            String quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String action,
            String reasonCode,
            String ownerId,
            long version,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        private static ActionReceipt from(
                DatabaseDurableWorkerQuarantineControlPlane.QuarantineHistoryRecord record) {
            return new ActionReceipt(record.historyId(), new DurableWorkerQuarantineKey(
                    record.key().runId(), record.key().checkpointFingerprint()),
                    record.quarantineReason().name(), record.consecutiveFailures(),
                    record.quarantineThreshold(), record.firstObservedAt(),
                    record.quarantinedAt(), record.action().name(), record.reasonCode(),
                    record.ownerId(), record.version(), record.actedAt(),
                    record.receiptFingerprint(), record.recordFingerprint());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
