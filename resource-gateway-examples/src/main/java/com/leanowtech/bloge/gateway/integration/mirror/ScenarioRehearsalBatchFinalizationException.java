package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Structured payload-free failure raised while preparing terminal batch evidence.
 *
 * <p>The finalization worker uses this closed vocabulary instead of guessing retryability from
 * provider exception classes or messages. Provider diagnostics and business material are
 * deliberately excluded so the failure can be persisted and exported safely.</p>
 */
public final class ScenarioRehearsalBatchFinalizationException
        extends IllegalStateException {
    private final Reason reason;

    /**
     * Creates one bounded finalization failure.
     *
     * @param reason stable retry and quarantine classification
     */
    public ScenarioRehearsalBatchFinalizationException(
            Reason reason) {
        super(Objects.requireNonNull(reason, "reason").failureCode());
        this.reason = reason;
    }

    /** @return stable payload-free failure classification */
    public Reason reason() {
        return reason;
    }

    /** Closed finalization failure vocabulary. */
    public enum Reason {
        SIGNER_UNAVAILABLE(
                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_SIGNER_UNAVAILABLE",
                true),
        SIGNATURE_INVALID(
                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_SIGNATURE_INVALID",
                false),
        MATERIAL_INVALID(
                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_MATERIAL_INVALID",
                false),
        CONTROL_UNAVAILABLE(
                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_CONTROL_UNAVAILABLE",
                true);

        private final String failureCode;
        private final boolean retryable;

        Reason(
                String failureCode,
                boolean retryable) {
            this.failureCode = failureCode;
            this.retryable = retryable;
        }

        /** @return stable safe code suitable for durable control state */
        public String failureCode() {
            return failureCode;
        }

        /** @return whether an automatic retry can plausibly recover */
        public boolean retryable() {
            return retryable;
        }
    }
}
