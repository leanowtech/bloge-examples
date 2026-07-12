package com.leanowtech.bloge.gateway.visual.runtime;

/** Stable internal failure classification mapped to integration problem details at the API boundary. */
public final class VisualPayloadGovernanceException extends RuntimeException {

    private final Reason reason;

    public VisualPayloadGovernanceException(Reason reason, String message) {
        super(message);
        this.reason = reason == null ? Reason.CORRUPT : reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND,
        ALREADY_EXISTS,
        HOLD_CONFLICT,
        LEGAL_HOLD_ACTIVE,
        SIGNING_UNAVAILABLE,
        CORRUPT
    }
}
