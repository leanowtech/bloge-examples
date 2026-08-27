package com.leanowtech.bloge.gateway.testing.world.fidelity;

/** Sanitized fail-closed error at the World fidelity boundary. */
public final class WorldFidelityException extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        ADMISSION_DENIED,
        SOURCE_DRIFT,
        DENOMINATOR_UNKNOWN,
        COMPARATOR_UNKNOWN,
        EXECUTION_FAILED,
        DRIFT_TRANSITION_INVALID,
        DRIFT_CAS_CONFLICT,
        APPROVAL_INVALID,
        PAYLOAD_UNAVAILABLE,
        PERSISTENCE_INVALID,
        EVIDENCE_INVALID
    }

    private final Code code;

    public WorldFidelityException(Code code) {
        super(code == null ? "WORLD_FIDELITY_INVALID" : code.name());
        this.code = code == null ? Code.INVALID_INPUT : code;
    }

    static WorldFidelityException of(Code code) {
        return new WorldFidelityException(code);
    }

    public Code code() {
        return code;
    }
}
