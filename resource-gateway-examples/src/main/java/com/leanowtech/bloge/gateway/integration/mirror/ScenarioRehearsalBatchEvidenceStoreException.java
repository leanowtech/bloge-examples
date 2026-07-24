package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Closed failure from the append-only Scenario batch evidence store.
 */
public final class ScenarioRehearsalBatchEvidenceStoreException
        extends RuntimeException {
    private final Reason reason;

    /**
     * Creates one payload-free classified store failure.
     *
     * @param reason stable failure class
     * @param message bounded operator-facing diagnostic without business payload
     * @param cause underlying infrastructure or integrity failure
     */
    public ScenarioRehearsalBatchEvidenceStoreException(
            Reason reason,
            String message,
            Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(
                reason, "reason");
    }

    /** @return stable store failure class */
    public Reason reason() {
        return reason;
    }

    /** Closed persistence failure vocabulary. */
    public enum Reason {
        CONFLICT,
        INTEGRITY_INVALID,
        VERIFICATION_UNAVAILABLE
    }
}
