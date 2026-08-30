package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

/**
 * Closed, HTTP-neutral failure vocabulary for the standalone Connection
 * facade. Messages are code-derived and never include request or credential
 * material.
 */
public final class ApiConnectionAuthoringFailure extends RuntimeException {
    /** Stable categories intentionally independent of a transport status map. */
    public enum Code {
        CAPABILITY_UNAVAILABLE, VALIDATION, NOT_FOUND, BUSY, LEASE_LOST, CONFLICT,
        CAS_MISMATCH, INTEGRITY, PERSISTENCE
    }

    private final Code code;
    private final java.time.Instant retryAt;

    /** Creates a safe failure for one stable category. */
    public ApiConnectionAuthoringFailure(Code code) {
        this(code, null);
    }

    /** Creates a safe failure with the claim authority's retry deadline. */
    public ApiConnectionAuthoringFailure(Code code, java.time.Instant retryAt) {
        super(message(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
        this.retryAt = retryAt;
    }

    /** @return stable failure category */
    public Code code() { return code; }
    /** @return authoritative claim retry deadline, or {@code null} when unavailable */
    public java.time.Instant retryAt() { return retryAt; }

    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }

    private static String message(Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> "connection authentication capability is unavailable";
            case VALIDATION -> "connection authoring request is invalid";
            case NOT_FOUND -> "connection revision was not found";
            case BUSY -> "connection command is busy";
            case LEASE_LOST -> "connection command lease was lost";
            case CONFLICT -> "connection command conflicts with an existing request";
            case CAS_MISMATCH -> "connection revision does not match";
            case INTEGRITY -> "connection authoring integrity check failed";
            case PERSISTENCE -> "connection authoring persistence failed";
        };
    }
}
