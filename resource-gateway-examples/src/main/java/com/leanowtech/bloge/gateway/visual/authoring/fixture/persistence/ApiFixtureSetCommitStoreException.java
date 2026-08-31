package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

/** Code-only failure raised by the private Fixture Set child store. */
public final class ApiFixtureSetCommitStoreException extends RuntimeException {
    private final Code code;

    /** Creates a payload-safe failure. */
    public ApiFixtureSetCommitStoreException(Code code) {
        super(code == null ? Code.INTEGRITY.name() : code.name());
        this.code = code == null ? Code.INTEGRITY : code;
    }

    /** @return stable application failure category */
    public Code code() { return code; }

    /** Closed persistence failure vocabulary. */
    public enum Code { LEASE_FENCED, LEASE_EXPIRED, STAGE_MISSING, CAS_MISMATCH, RECEIPT_INVALID, INTEGRITY }
}
