package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

/** Payload-free application failure for private Fixture Set reads. */
public final class ApiFixtureSetAuthoringFailure extends RuntimeException {
    private final Code code;

    /** Creates one code-only failure safe for transport mapping. */
    public ApiFixtureSetAuthoringFailure(Code code) {
        super(code == null ? Code.INTEGRITY.name() : code.name());
        this.code = code == null ? Code.INTEGRITY : code;
    }

    /** @return stable closed failure category */
    public Code code() { return code; }

    /** Closed private Fixture authoring failure vocabulary. */
    public enum Code {
        CAPABILITY_UNAVAILABLE, VALIDATION, NOT_FOUND, CAS_MISMATCH, CONFLICT, INTEGRITY, PERSISTENCE
    }
}
