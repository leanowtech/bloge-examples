package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

/**
 * Closed, HTTP-neutral failure vocabulary for the standalone Connection
 * facade. Messages are code-derived and never include request or credential
 * material.
 */
public final class ApiConnectionAuthoringFailure extends RuntimeException {
    /** Stable categories intentionally independent of a transport status map. */
    public enum Code {
        CAPABILITY_UNAVAILABLE, VALIDATION, NOT_FOUND, BUSY, CONFLICT,
        CAS_MISMATCH, INTEGRITY, PERSISTENCE
    }

    private final Code code;

    /** Creates a safe failure for one stable category. */
    public ApiConnectionAuthoringFailure(Code code) {
        super(message(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
    }

    /** @return stable failure category */
    public Code code() { return code; }

    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }

    private static String message(Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> "connection authentication capability is unavailable";
            case VALIDATION -> "connection authoring request is invalid";
            case NOT_FOUND -> "connection revision was not found";
            case BUSY -> "connection command is busy";
            case CONFLICT -> "connection command conflicts with an existing request";
            case CAS_MISMATCH -> "connection revision does not match";
            case INTEGRITY -> "connection authoring integrity check failed";
            case PERSISTENCE -> "connection authoring persistence failed";
        };
    }
}
