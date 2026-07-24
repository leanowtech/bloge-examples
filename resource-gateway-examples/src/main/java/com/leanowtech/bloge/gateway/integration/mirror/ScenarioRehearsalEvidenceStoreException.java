package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Stable failure classification at the Scenario aggregate evidence boundary.
 *
 * <p>Conflict, corrupt material, and verification-authority outage require different retry and
 * operator responses. Repository adapters use this type instead of exposing database-vendor
 * exceptions or collapsing all integrity failures into a generic availability error.</p>
 */
public final class ScenarioRehearsalEvidenceStoreException
        extends RuntimeException {
    private final Reason reason;

    /**
     * Creates one payload-free store failure.
     *
     * @param reason stable machine-interpretable classification
     * @param message bounded payload-free diagnostic
     * @param cause internal cause, never serialized by the integration API
     */
    public ScenarioRehearsalEvidenceStoreException(
            Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** @return stable failure classification */
    public Reason reason() {
        return reason;
    }

    /** Failure classes with distinct retry and incident-handling semantics. */
    public enum Reason {
        /** The stable run identity already names different immutable material. */
        CONFLICT,
        /** Persisted or caller-supplied evidence failed identity or signature verification. */
        INTEGRITY_INVALID,
        /** The configured verification key authority cannot currently answer. */
        VERIFICATION_UNAVAILABLE
    }
}
