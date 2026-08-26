package com.leanowtech.bloge.gateway.testing.world.draft;

/** Sanitized, fail-closed error for World draft capture and promotion. */
public final class WorldDraftCandidateException extends IllegalArgumentException {
    public enum Code {
        INVALID_INPUT,
        INLINE_PAYLOAD_UNSUPPORTED,
        SOURCE_NOT_AUTHORIZED,
        SOURCE_NOT_FOUND,
        SOURCE_EXPIRED,
        SOURCE_INTEGRITY,
        SOURCE_POLICY_DENIED,
        SOURCE_READ_FAILED,
        REDACTION_REQUIRED,
        CANDIDATE_NOT_FOUND,
        STATE_TRANSITION_INVALID,
        CAS_CONFLICT,
        APPROVAL_INVALID,
        APPROVAL_STALE,
        MATERIALIZATION_INVALID,
        PUBLICATION_INVALID,
        LIMIT_EXCEEDED
    }

    private final Code code;

    public WorldDraftCandidateException(Code code) {
        super("RG.WORLD.DRAFT." + (code == null ? Code.INVALID_INPUT.name() : code.name()));
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    public Code code() {
        return code;
    }

    public String wireCode() {
        return getMessage();
    }
}
