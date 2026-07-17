package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Payload-free projection of one atomically committed durable recovery step.
 *
 * @param schemaVersion recovery-step response protocol version
 * @param runId governed durable run identity
 * @param outcome server-derived suspended or terminal BLOGE outcome
 * @param status resulting durable control status
 * @param ownerId recovery owner that committed the transition
 * @param leaseEpoch positive ownership generation
 * @param revision resulting control revision
 * @param observedAt database-authority transition time
 * @param checkpointFingerprint exact resulting checkpoint identity
 * @param boundary resulting payload-free BLOGE boundary coordinates
 * @param terminal terminal receipt projection, present only for terminal outcomes
 * @param idempotentReplay whether an earlier immutable result was replayed
 */
public record DurableTestRecoveryStepResponse(
        String schemaVersion,
        String runId,
        String outcome,
        String status,
        String ownerId,
        long leaseEpoch,
        long revision,
        Instant observedAt,
        String checkpointFingerprint,
        Boundary boundary,
        Terminal terminal,
        boolean idempotentReplay
) {
    /** Current one-signal recovery-step response protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoveryStepResponse.v1";

    /** Enforces a complete payload-free suspended or terminal projection. */
    public DurableTestRecoveryStepResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        runId = normalized(runId);
        outcome = normalized(outcome);
        status = normalized(status);
        ownerId = normalized(ownerId);
        checkpointFingerprint = normalized(checkpointFingerprint);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        boundary = Objects.requireNonNull(boundary, "boundary");
        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome parsedOutcome;
        DurableTestExecutionCheckpoint.Status parsedStatus;
        try {
            parsedOutcome = DurableTestExecutionCheckpointRepository.RecoveryStepOutcome
                    .valueOf(outcome);
            parsedStatus = DurableTestExecutionCheckpoint.Status.valueOf(status);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Recovery-step outcome and status must be protocol values", invalid);
        }
        boolean terminalShape = parsedOutcome.terminal()
                && parsedStatus == DurableTestExecutionCheckpoint.Status.TERMINAL
                && terminal != null;
        boolean suspendedShape = parsedOutcome
                == DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED
                && parsedStatus == DurableTestExecutionCheckpoint.Status.SUSPENDED
                && terminal == null;
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || runId.isBlank()
                || ownerId.isBlank()
                || leaseEpoch <= 0
                || revision < 1
                || checkpointFingerprint.isBlank()
                || !(terminalShape || suspendedShape)) {
            throw new IllegalArgumentException(
                    "A complete suspended or terminal recovery-step result is required");
        }
        if (terminalShape && !terminal.executionOutcome().equals(outcome)) {
            throw new IllegalArgumentException(
                    "Terminal receipt projection must agree with the recovery-step outcome");
        }
    }

    /**
     * Projects a verified immutable repository result without business or engine payload.
     *
     * @param result suspended or terminal recovery-step result
     * @return payload-free public projection
     */
    public static DurableTestRecoveryStepResponse from(
            DurableTestExecutionCheckpointRepository.RecoveryStepResult result) {
        Objects.requireNonNull(result, "result");
        DurableTestExecutionCheckpoint checkpoint = result.checkpoint();
        DurableTestExecutionCheckpoint.EngineState engine = checkpoint.engineState();
        DurableTestRecoveryTerminalReceipt receipt = result.terminalReceipt();
        return new DurableTestRecoveryStepResponse(
                "", checkpoint.runId(), result.outcome().name(),
                checkpoint.lifecycle().status().name(), checkpoint.lifecycle().ownerId(),
                checkpoint.lifecycle().leaseEpoch(), checkpoint.lifecycle().revision(),
                checkpoint.lifecycle().updatedAt(), checkpoint.checkpointFingerprint(),
                new Boundary(engine.nodeId(), engine.boundaryType(),
                        engine.boundarySequence(), engine.stateVersion()),
                receipt == null ? null : new Terminal(
                        receipt.executionOutcome().name(), receipt.completedAt(),
                        receipt.receiptFingerprint(), receipt.evidenceStatus(),
                        receipt.evidenceGapCodes()),
                result.idempotentReplay());
    }

    /**
     * Payload-free location of the resulting BLOGE boundary.
     *
     * @param nodeId stable boundary node
     * @param boundaryType normalized BLOGE boundary kind
     * @param boundarySequence positive monotonic boundary sequence
     * @param stateVersion non-negative engine-state version
     */
    public record Boundary(
            String nodeId,
            String boundaryType,
            long boundarySequence,
            long stateVersion
    ) {
        /** Requires complete stable boundary coordinates. */
        public Boundary {
            nodeId = normalized(nodeId);
            boundaryType = normalized(boundaryType);
            if (nodeId.isBlank() || boundaryType.isBlank()
                    || boundarySequence <= 0 || stateVersion < 0) {
                throw new IllegalArgumentException(
                        "Complete recovery-step boundary coordinates are required");
            }
        }
    }

    /**
     * Promotion-blocking terminal receipt projection.
     *
     * @param executionOutcome terminal BLOGE outcome
     * @param completedAt database-authority completion time
     * @param receiptFingerprint exact immutable receipt identity
     * @param evidenceStatus fixed incomplete-evidence status
     * @param evidenceGapCodes explicit reasons complete evidence is unavailable
     */
    public record Terminal(
            String executionOutcome,
            Instant completedAt,
            String receiptFingerprint,
            String evidenceStatus,
            List<String> evidenceGapCodes
    ) {
        /** Requires a complete promotion-blocking terminal projection. */
        public Terminal {
            executionOutcome = normalized(executionOutcome);
            receiptFingerprint = normalized(receiptFingerprint);
            evidenceStatus = normalized(evidenceStatus);
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            evidenceGapCodes = evidenceGapCodes == null
                    ? List.of() : List.copyOf(evidenceGapCodes);
            if (executionOutcome.isBlank()
                    || receiptFingerprint.isBlank()
                    || !DurableTestRecoveryTerminalReceipt.EVIDENCE_STATUS.equals(
                    evidenceStatus)
                    || evidenceGapCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "A complete promotion-blocking terminal projection is required");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
