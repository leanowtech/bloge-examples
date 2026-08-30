package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

/** Safe, payload-free failure from the Connection commit state machine. */
public final class ApiConnectionCommitStoreException extends RuntimeException {
    /** Stable categories for callers and future JDBC adapters. */
    public enum Code { LEASE_FENCED, LEASE_EXPIRED, STAGE_MISSING, CAS_MISMATCH, INTEGRITY }

    private final Code code;

    /** Creates an error whose message is derived only from its stable code. */
    public ApiConnectionCommitStoreException(Code code) {
        super(safeMessage(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
    }

    /** @return stable machine-readable code */
    public Code code() { return code; }

    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }

    private static String safeMessage(Code code) {
        return switch (code) {
            case LEASE_FENCED -> "connection command lease is fenced";
            case LEASE_EXPIRED -> "connection command lease is expired";
            case STAGE_MISSING -> "staged connection is missing";
            case CAS_MISMATCH -> "connection revision does not match";
            case INTEGRITY -> "connection commit integrity check failed";
        };
    }
}
