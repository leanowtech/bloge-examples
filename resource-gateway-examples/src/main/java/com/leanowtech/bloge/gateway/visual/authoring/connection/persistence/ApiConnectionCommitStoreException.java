package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

/** Safe, payload-free failure from the Connection commit state machine. */
public final class ApiConnectionCommitStoreException extends RuntimeException {
    /** Stable categories for callers and future JDBC adapters. */
    public enum Code { LEASE_FENCED, LEASE_EXPIRED, STAGE_MISSING, CAS_MISMATCH, INTEGRITY }

    private final Code code;

    /** Creates an error with a safe message; callers must not provide payload data. */
    public ApiConnectionCommitStoreException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    /** @return stable machine-readable code */
    public Code code() { return code; }

    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }
}
