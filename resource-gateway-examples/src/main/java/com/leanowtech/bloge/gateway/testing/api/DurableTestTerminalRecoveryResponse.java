package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Payload-free projection of one atomically committed terminal recovery.
 *
 * @param schemaVersion terminal-recovery response protocol version
 * @param runId governed durable run identity
 * @param status terminal control-checkpoint status
 * @param executionOutcome normalized BLOGE terminal outcome
 * @param ownerId recovery owner that committed the terminal transition
 * @param leaseEpoch positive ownership generation
 * @param revision terminal control revision
 * @param completedAt database-authority completion time
 * @param terminalCheckpointFingerprint exact terminal checkpoint identity
 * @param terminalReceiptFingerprint exact promotion-blocking receipt identity
 * @param evidenceStatus fixed incomplete-evidence status
 * @param evidenceGapCodes explicit reasons complete evidence is unavailable
 * @param idempotentReplay whether an earlier committed terminal result was replayed
 */
public record DurableTestTerminalRecoveryResponse(
        String schemaVersion,
        String runId,
        String status,
        String executionOutcome,
        String ownerId,
        long leaseEpoch,
        long revision,
        Instant completedAt,
        String terminalCheckpointFingerprint,
        String terminalReceiptFingerprint,
        String evidenceStatus,
        List<String> evidenceGapCodes,
        boolean idempotentReplay
) {
    /** Current terminal-recovery response protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestTerminalRecoveryResponse.v1";

    /** Requires a complete payload-free terminal projection. */
    public DurableTestTerminalRecoveryResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        runId = normalized(runId);
        status = normalized(status);
        executionOutcome = normalized(executionOutcome);
        ownerId = normalized(ownerId);
        terminalCheckpointFingerprint = normalized(terminalCheckpointFingerprint);
        terminalReceiptFingerprint = normalized(terminalReceiptFingerprint);
        evidenceStatus = normalized(evidenceStatus);
        evidenceGapCodes = evidenceGapCodes == null ? List.of() : List.copyOf(evidenceGapCodes);
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || runId.isBlank()
                || !DurableTestExecutionCheckpoint.Status.TERMINAL.name().equals(status)
                || executionOutcome.isBlank()
                || ownerId.isBlank()
                || leaseEpoch <= 0
                || revision < 1
                || terminalCheckpointFingerprint.isBlank()
                || terminalReceiptFingerprint.isBlank()
                || !DurableTestRecoveryTerminalReceipt.EVIDENCE_STATUS.equals(evidenceStatus)
                || evidenceGapCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A complete promotion-blocking terminal recovery result is required");
        }
    }

    /**
     * Projects the verified immutable repository result without business or engine payload.
     *
     * @param result terminal checkpoint and receipt
     * @return payload-free terminal recovery response
     */
    public static DurableTestTerminalRecoveryResponse from(
            DurableTestExecutionCheckpointRepository.RecoveryTerminalResult result) {
        Objects.requireNonNull(result, "result");
        DurableTestExecutionCheckpoint checkpoint = result.checkpoint();
        DurableTestRecoveryTerminalReceipt receipt = result.receipt();
        return new DurableTestTerminalRecoveryResponse(
                "", checkpoint.runId(), checkpoint.lifecycle().status().name(),
                receipt.executionOutcome().name(), checkpoint.lifecycle().ownerId(),
                checkpoint.lifecycle().leaseEpoch(), checkpoint.lifecycle().revision(),
                receipt.completedAt(), checkpoint.checkpointFingerprint(),
                receipt.receiptFingerprint(), receipt.evidenceStatus(),
                receipt.evidenceGapCodes(), result.idempotentReplay());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
