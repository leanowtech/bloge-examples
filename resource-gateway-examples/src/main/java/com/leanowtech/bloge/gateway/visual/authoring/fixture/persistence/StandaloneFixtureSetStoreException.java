package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

/** Code-only failure raised by standalone Fixture Set persistence. */
public final class StandaloneFixtureSetStoreException extends RuntimeException {
    private final Code code;

    public StandaloneFixtureSetStoreException(Code code) {
        super(code == null ? Code.INTEGRITY.name() : code.name());
        this.code = code == null ? Code.INTEGRITY : code;
    }

    public Code code() { return code; }

    public enum Code { CAS_MISMATCH, CONFLICT, INTEGRITY, PERSISTENCE }
}
