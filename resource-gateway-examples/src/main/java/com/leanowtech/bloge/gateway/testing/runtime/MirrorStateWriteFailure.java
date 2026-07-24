package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidence;

import java.util.Objects;

/**
 * Payload-free terminal failure classification for one graph-embedded Session write attempt.
 *
 * <p>The exception deliberately carries no business input, provider message, entity identity, or
 * command response. It separates failures that are proven not to have committed from failures
 * whose durable outcome cannot be established locally. Callers must never downgrade
 * {@link MirrorStateWriteOutcomeRunEvidence.WriteOutcome#COMMIT_OUTCOME_UNKNOWN} to a rejection
 * merely because the in-process Session head did not advance.</p>
 */
public final class MirrorStateWriteFailure extends RuntimeException {
    private final MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome;
    private final MirrorStateWriteOutcomeRunEvidence.WriteStage stage;
    private final String code;
    private final String errorType;
    private final boolean retryable;

    /**
     * Creates one normalized write failure.
     *
     * @param outcome rejected, pre-commit failed, or commit-outcome-unknown classification
     * @param stage last trustworthy write-processing stage
     * @param code stable machine-readable payload-free error code
     * @param errorType bounded normalized error family
     * @param retryable whether the governed runtime permits another delegate attempt
     */
    public MirrorStateWriteFailure(
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            String code,
            String errorType,
            boolean retryable) {
        super(validCode(code), null, false, false);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.stage = Objects.requireNonNull(stage, "stage");
        this.code = validCode(code);
        this.errorType = bounded(errorType, "errorType", 128);
        this.retryable = retryable;
        if (outcome == MirrorStateWriteOutcomeRunEvidence.WriteOutcome.COMMITTED
                || outcome == MirrorStateWriteOutcomeRunEvidence.WriteOutcome.REPLAYED) {
            throw new IllegalArgumentException(
                    "write failure cannot claim a successful outcome");
        }
        if (outcome
                == MirrorStateWriteOutcomeRunEvidence.WriteOutcome.REJECTED
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .RESOLVER_ADMISSION
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMAND_ADMISSION
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMAND_EVALUATION) {
            throw new IllegalArgumentException(
                    "rejected write has an invalid terminal stage");
        }
        if (outcome
                == MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                .PRE_COMMIT_FAILED
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMAND_ADMISSION
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMAND_EVALUATION
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMIT) {
            throw new IllegalArgumentException(
                    "pre-commit failure has an invalid terminal stage");
        }
        if (outcome
                == MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                .COMMIT_OUTCOME_UNKNOWN
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .COMMIT
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .RESULT_VERIFICATION
                && stage != MirrorStateWriteOutcomeRunEvidence.WriteStage
                .PROCESS_INTERRUPTION) {
            throw new IllegalArgumentException(
                    "unknown commit outcome has an invalid terminal stage");
        }
    }

    /** @return conservative terminal write outcome */
    public MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome() {
        return outcome;
    }

    /** @return last trustworthy write-processing stage */
    public MirrorStateWriteOutcomeRunEvidence.WriteStage stage() {
        return stage;
    }

    /** @return stable payload-free error code */
    public String code() {
        return code;
    }

    /** @return normalized error family */
    public String errorType() {
        return errorType;
    }

    /** @return whether another governed delegate attempt is allowed */
    public boolean retryable() {
        return retryable;
    }

    private static String validCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Z][A-Z0-9_.-]{0,191}")) {
            throw new IllegalArgumentException(
                    "write failure code is invalid");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximum
                || !normalized.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(
                    field + " must be a bounded machine-readable value");
        }
        return normalized;
    }
}
