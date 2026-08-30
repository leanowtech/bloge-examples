package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/** Stable fail-closed error from the resource commit state machine. */
public final class ApiResourceCommitStoreException extends RuntimeException {
    /** Machine-readable commit failure categories. */
    public enum Code { LEASE_FENCED, LEASE_EXPIRED, STAGE_MISSING, CAS_MISMATCH, PROJECTION_INVALID, RECEIPT_INVALID, INTEGRITY }
    private final Code code;
    /** @param code stable category @param message safe diagnostic */
    public ApiResourceCommitStoreException(Code code, String message) { super(message); this.code = code; }
    /** @return stable machine-readable code */
    public Code code() { return code; }
}
